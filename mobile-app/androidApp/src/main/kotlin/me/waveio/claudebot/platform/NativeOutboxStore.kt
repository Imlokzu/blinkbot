package me.waveio.claudebot.platform

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.waveio.claudebot.R
import me.waveio.claudebot.data.normalizeApiOrigin
import me.waveio.claudebot.state.OutboxItem

/** The default app and WorkManager share a process and this reentrant monitor. */
internal object NativePreferenceTransactions { val lock = Any() }

/** Native transaction contract; the matching common bridge hook can delegate to this. */
interface NativeAtomicPreferences {
    fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>)
}

internal class NativeOutboxStore(
    private val context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences("native-preferences", Context.MODE_PRIVATE),
    private val metadata: SharedPreferences = context.getSharedPreferences("native-outbox-meta", Context.MODE_PRIVATE),
    private val secrets: AndroidSecureStorage = AndroidSecureStorage(context),
) : OutboxDeliveryStore {
    private val lock get() = NativePreferenceTransactions.lock
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Migrate existing coherent credentials only once; interrupted re-pairing stays disabled. */
    fun initialize() = synchronized(lock) {
        if (!metadata.contains("epoch")) {
            save(metadata.edit().putString("epoch", UUID.randomUUID().toString()).putBoolean("ready", false))
        }
        if (!metadata.getBoolean("ready", false) && !metadata.getBoolean("credential_pending", false)) bindCurrent()
    }

    fun readPreference(key: String): String? = synchronized(lock) {
        if (key.endsWith(QUEUE_SUFFIX)) {
            val device = key.removeSuffix(QUEUE_SUFFIX)
            val identity = snapshot()
            if (identity?.deviceId == device) {
                val owner = binding(identity)
                val owners = owners(device)
                return@synchronized json.encodeToString(queue(device).filter { owners[it.clientId] == owner })
            }
            return@synchronized json.encodeToString(emptyList<OutboxItem>())
        }
        preferences.getString(key, null)
    }

    fun readSecret(key: String): String? = synchronized(lock) {
        // A cold foreground controller must not combine a new token with an old origin
        // after the process dies between the three pairing writes.
        if (key == "device_token") snapshot()?.token else secrets.read(key)
    }

    fun writeSecret(key: String, value: String?) = synchronized(lock) {
        if (key == "device_token") invalidate()
        secrets.write(key, value)
        // Mark success only after ciphertext is committed. A crash or failed
        // write must not let the final server write bind the previous token.
        if (key == "device_token" && !value.isNullOrBlank()) {
            save(metadata.edit().putBoolean("token_written", true))
        }
    }

    fun writePreference(key: String, value: String?): Boolean = synchronized(lock) {
        val old = preferences.getString(key, null)
        if (key == "device_id" && old != value) invalidate()
        if (key == "server" && old != value) invalidate(metadata.getBoolean("token_written", false))
        val changed = writeChanges(mapOf(key to value), allowErrorClearing = false)
        if (key == "server" && metadata.getBoolean("token_written", false)) bindCurrent()
        changed || key == "server" || key == "device_id"
    }

    /** Transforms latest persisted values, commits them together, then returns outside the lock. */
    fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>): Boolean = synchronized(lock) {
        val declared = keys.distinct()
        require(declared.none { it == "device_id" || it == "server" })
        val identity = snapshot()
        val changes = transform(declared.associateWith(::readPreference))
        require(changes.keys.all { it in declared })
        check(snapshot() == identity) { context.getString(R.string.native_storage_error) }
        writeChanges(changes, allowErrorClearing = true)
    }

    /** Called only by an observed process foreground -> background transition. */
    fun authorizeBackground(): Boolean = synchronized(lock) {
        val identity = snapshot() ?: return@synchronized false
        if (metadata.getString("blocked_epoch", null) == identity.epoch) return@synchronized false
        val owner = binding(identity)
        val ownerMap = owners(identity.deviceId)
        val receipts = strings(identity.deviceId + ACK_SUFFIX).toSet()
        val candidates = queue(identity.deviceId).filter {
            it.lastError == null && !it.deliveryDeclined && ownerMap[it.clientId] == owner && it.clientId !in receipts
        }.map { it.clientId }.toSet()
        val allowed = (strings(identity.deviceId + ALLOWED_SUFFIX) + candidates).filter { it in candidates }.distinct()
        writeChanges(mapOf(identity.deviceId + ALLOWED_SUFFIX to json.encodeToString(allowed)), allowErrorClearing = false)
    }

    fun snapshot(): OutboxIdentity? = synchronized(lock) {
        if (!metadata.getBoolean("ready", false)) return@synchronized null
        val identity = rawIdentity() ?: return@synchronized null
        if (metadata.getString("binding", null) != binding(identity)) return@synchronized null
        identity
    }

    override fun isCurrent(identity: OutboxIdentity): Boolean = synchronized(lock) { snapshot() == identity }

    override fun eligible(identity: OutboxIdentity): List<OutboxItem> = synchronized(lock) {
        if (!isCurrent(identity) || metadata.getString("blocked_epoch", null) == identity.epoch) return@synchronized emptyList()
        val allowed = strings(identity.deviceId + ALLOWED_SUFFIX).toSet()
        val acknowledged = strings(identity.deviceId + ACK_SUFFIX).toSet()
        val failures = objectValue(identity.deviceId + FAILED_SUFFIX)
        val owners = owners(identity.deviceId)
        val owner = binding(identity)
        queue(identity.deviceId).filter { item ->
            item.lastError == null && !item.deliveryDeclined && item.clientId in allowed && item.clientId !in acknowledged && owners[item.clientId] == owner &&
                failures[item.clientId]?.jsonObject?.get("fingerprint")?.jsonPrimitive?.contentOrNull != fingerprint(item)
        }
    }

    override fun acknowledge(identity: OutboxIdentity, item: OutboxItem): Boolean = synchronized(lock) {
        if (!isCurrent(identity) || owners(identity.deviceId)[item.clientId] != binding(identity)) return@synchronized false
        val current = queue(identity.deviceId)
        if (current.none { it == item }) return@synchronized false
        val allowed = strings(identity.deviceId + ALLOWED_SUFFIX).filterNot { it == item.clientId }
        val acknowledged = (strings(identity.deviceId + ACK_SUFFIX) + item.clientId).distinct()
        val failures = objectValue(identity.deviceId + FAILED_SUFFIX).toMutableMap().apply { remove(item.clientId) }
        val edit = preferences.edit()
            .putString(identity.deviceId + QUEUE_SUFFIX, json.encodeToString(current.filterNot { it == item }))
            .putString(identity.deviceId + ALLOWED_SUFFIX, json.encodeToString(allowed))
            .putString(identity.deviceId + ACK_SUFFIX, json.encodeToString(acknowledged))
            .apply {
                if (failures.isEmpty()) remove(identity.deviceId + FAILED_SUFFIX)
                else putString(identity.deviceId + FAILED_SUFFIX, JsonObject(failures).toString())
            }
        save(edit)
        true
    }

    override fun permanentFailure(identity: OutboxIdentity, item: OutboxItem, status: Int, code: String) = synchronized(lock) {
        if (!isCurrent(identity) || owners(identity.deviceId)[item.clientId] != binding(identity) ||
            queue(identity.deviceId).none { it == item }) return@synchronized
        val safeCode = code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) } ?: "delivery_error"
        val failed = item.copy(lastError = safeCode)
        val failures = objectValue(identity.deviceId + FAILED_SUFFIX).toMutableMap()
        failures[item.clientId] = buildJsonObject {
            put("status", status); put("code", safeCode)
            put("fingerprint", fingerprint(failed))
        }
        val current = queue(identity.deviceId).map { if (it == item) failed else it }
        // Fail closed across process death between the two preference files.
        if (status == 401 || status == 403) save(metadata.edit().putString("blocked_epoch", identity.epoch))
        save(preferences.edit().putString(identity.deviceId + FAILED_SUFFIX, JsonObject(failures).toString())
            .putString(identity.deviceId + QUEUE_SUFFIX, json.encodeToString(current))
            .putString(identity.deviceId + ALLOWED_SUFFIX, json.encodeToString(strings(identity.deviceId + ALLOWED_SUFFIX).filterNot { it == item.clientId })))
    }

    private fun writeChanges(changes: Map<String, String?>, allowErrorClearing: Boolean): Boolean {
        val edit = preferences.edit()
        var changed = false
        var unblockEpoch: String? = null
        for ((key, requested) in changes) {
            var value = requested
            if (key.endsWith(QUEUE_SUFFIX) && value != null) {
                val device = key.removeSuffix(QUEUE_SUFFIX)
                val receipts = strings(device + ACK_SUFFIX).toSet()
                val incoming = decodeQueue(value).filterNot { it.clientId in receipts }
                val existing = queue(device)
                val ownerMap = owners(device).toMutableMap()
                val identity = snapshot()?.takeIf { it.deviceId == device }
                val owner = identity?.let(::binding) ?: "unbound"
                incoming.forEach { item -> if (item.clientId !in ownerMap) ownerMap[item.clientId] = owner }
                // A re-pair using the same device id must not relabel old-account entries.
                val retainedOldAccount = existing.filter { ownerMap[it.clientId] != owner && it.clientId !in receipts }
                // Legacy whole-list caches cannot know which entries arrived since
                // their read. Only an atomic transform may delete omitted entries.
                val incomingIds = incoming.map { it.clientId }.toSet()
                val retainedNewer = if (allowErrorClearing) emptyList() else existing.filter {
                    ownerMap[it.clientId] == owner && it.clientId !in incomingIds && it.clientId !in receipts
                }
                val failures = objectValue(device + FAILED_SUFFIX).toMutableMap()
                val currentAccount = incoming.filter { ownerMap[it.clientId] == owner }.map { item ->
                    val previous = existing.firstOrNull { it.clientId == item.clientId }
                    val protected = if (!allowErrorClearing && previous?.deliveryDeclined == true) {
                        item.copy(deliveryDeclined = true)
                    } else item
                    if (previous?.lastError != null && item.lastError == null) {
                        if (allowErrorClearing) {
                            failures.remove(item.clientId)
                            if (metadata.getString("blocked_epoch", null) == identity?.epoch) unblockEpoch = identity?.epoch
                            protected
                        } else protected.copy(lastError = previous.lastError)
                    } else protected
                }
                val failureValue = failures.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
                if (preferences.getString(device + FAILED_SUFFIX, null) != failureValue) {
                    if (failureValue == null) edit.remove(device + FAILED_SUFFIX)
                    else edit.putString(device + FAILED_SUFFIX, failureValue)
                    changed = true
                }
                value = json.encodeToString((retainedOldAccount + currentAccount + retainedNewer).distinctBy { it.clientId })
                save(metadata.edit().putString("owners.$device", JsonObject(ownerMap.mapValues { JsonPrimitive(it.value) }).toString()))
            }
            if (key.endsWith(ALLOWED_SUFFIX) && value != null) {
                val device = key.removeSuffix(ALLOWED_SUFFIX)
                val receipts = strings(device + ACK_SUFFIX).toSet()
                value = json.encodeToString(decodeStrings(value).filterNot { it in receipts }.distinct())
            }
            if (preferences.getString(key, null) == value) continue
            changed = true
            if (value == null) edit.remove(key) else edit.putString(key, value)
        }
        if (changed) save(edit)
        // Removing the account block before persisting the explicit retry would
        // allow other entries to send after a partially failed transaction.
        if (unblockEpoch != null && metadata.getString("blocked_epoch", null) == unblockEpoch) {
            save(metadata.edit().remove("blocked_epoch"))
        }
        return changed
    }

    private fun invalidate(tokenWillBeWritten: Boolean = false) {
        save(metadata.edit().putString("epoch", UUID.randomUUID().toString())
            .putBoolean("ready", false).putBoolean("credential_pending", true)
            .putBoolean("token_written", tokenWillBeWritten).remove("blocked_epoch"))
    }

    private fun bindCurrent() {
        val identity = rawIdentity() ?: return
        val owner = binding(identity)
        val ownerMap = owners(identity.deviceId).toMutableMap()
        queue(identity.deviceId).forEach { ownerMap.putIfAbsent(it.clientId, owner) }
        save(metadata.edit().putBoolean("ready", true).putBoolean("token_written", false)
            .putBoolean("credential_pending", false)
            .putString("binding", owner).putString("owners.${identity.deviceId}", JsonObject(ownerMap.mapValues { JsonPrimitive(it.value) }).toString()))
    }

    private fun rawIdentity(): OutboxIdentity? {
        val device = preferences.getString("device_id", null)?.takeIf { it.isNotBlank() } ?: return null
        val server = preferences.getString("server", null) ?: return null
        val token = secrets.read("device_token")?.takeIf { it.isNotBlank() } ?: return null
        val origin = runCatching { normalizeApiOrigin(server) }.getOrNull() ?: return null
        val epoch = metadata.getString("epoch", null) ?: return null
        return OutboxIdentity(device, origin, token, epoch)
    }

    private fun binding(identity: OutboxIdentity) = digest("${identity.deviceId}\u0000${identity.server}\u0000${identity.token}")
    private fun fingerprint(item: OutboxItem) = digest(json.encodeToString(item))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun queue(device: String) = preferences.getString(device + QUEUE_SUFFIX, null)?.let(::decodeQueue).orEmpty()
    private fun decodeQueue(value: String): List<OutboxItem> = decode { json.decodeFromString(value) }
    private fun strings(key: String) = preferences.getString(key, null)?.let(::decodeStrings).orEmpty()
    private fun decodeStrings(value: String): List<String> = decode { json.decodeFromString(value) }
    private fun objectValue(key: String): JsonObject = preferences.getString(key, null)?.let { decode { json.parseToJsonElement(it).jsonObject } } ?: JsonObject(emptyMap())
    private fun owners(device: String): Map<String, String> = metadata.getString("owners.$device", null)?.let { value ->
        decode { json.parseToJsonElement(value).jsonObject.mapValues { it.value.jsonPrimitive.content } }
    }.orEmpty()
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (_: Exception) { throw IllegalStateException(context.getString(R.string.native_storage_error)) }
    private fun save(editor: SharedPreferences.Editor) { check(editor.commit()) { context.getString(R.string.native_storage_error) } }

    companion object {
        const val QUEUE_SUFFIX = ".outbox.v1"
        const val ALLOWED_SUFFIX = ".outbox.allowed"
        const val ACK_SUFFIX = ".outbox.ack.v1"
        const val FAILED_SUFFIX = ".outbox.failed.v1"
    }
}
