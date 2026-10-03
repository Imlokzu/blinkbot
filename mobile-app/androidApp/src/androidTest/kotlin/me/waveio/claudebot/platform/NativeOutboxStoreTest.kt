package me.waveio.claudebot.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.waveio.claudebot.state.OutboxItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real preference files/Keystore; no sender or backend is invoked. */
@RunWith(AndroidJUnit4::class)
class NativeOutboxStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val prefsName = "outbox-fixture-$id"
    private val metaName = "outbox-meta-fixture-$id"
    private val secretName = "outbox-secret-fixture-$id"
    private val alias = "me.waveio.claudebot.outbox-test.$id"
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val meta = context.getSharedPreferences(metaName, Context.MODE_PRIVATE)
    private val secrets = AndroidSecureStorage(context, alias, secretName)
    private val json = Json { encodeDefaults = true }
    private lateinit var store: NativeOutboxStore
    private val device = "fixture-device"
    private val queueKey = device + NativeOutboxStore.QUEUE_SUFFIX
    private val allowedKey = device + NativeOutboxStore.ALLOWED_SUFFIX

    @Before fun setUp() { store = newStore(); store.initialize(); pair() }
    @After fun tearDown() {
        listOf(prefsName, metaName, secretName).forEach(context::deleteSharedPreferences)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    private fun newStore() = NativeOutboxStore(context, prefs, meta, secrets)
    private fun item(id: String) = OutboxItem(id, "fixture-session", "Fixture", "fixture/model", "low")
    private fun pair(token: String = "fixture-token", server: String = "https://fixture.invalid") {
        store.writePreference("device_id", device)
        store.writeSecret("device_token", token)
        store.writePreference("server", server)
    }
    private fun persist(vararg entries: OutboxItem) {
        store.writePreference(queueKey, json.encodeToString(entries.toList()))
        store.writePreference(allowedKey, json.encodeToString(entries.map { it.clientId }))
    }
    private fun queue() = json.decodeFromString<List<OutboxItem>>(store.readPreference(queueKey)!!)

    @Test fun permissionIsRequiredAndIdentityNeverSerializesToken() {
        val original = item("one")
        store.writePreference(queueKey, json.encodeToString(listOf(original)))
        val identity = store.snapshot()!!
        assertTrue(store.eligible(identity).isEmpty())
        store.writePreference(allowedKey, "[\"one\"]")
        assertEquals(listOf(original), store.eligible(identity))
        assertFalse(identity.toString().contains(identity.token))
        assertFalse(meta.all.toString().contains(identity.token))
    }

    @Test fun acknowledgementPreservesNewItemsAndPreventsStaleCacheResurrection() {
        val first = item("one"); val newer = item("two")
        persist(first)
        val identity = store.snapshot()!!
        persist(first, newer)
        assertTrue(store.acknowledge(identity, first))
        assertEquals(listOf(newer), queue())
        // A foreground coroutine still holding the acknowledged item cannot reintroduce it.
        persist(first, newer)
        assertEquals(listOf(newer), queue())
        assertEquals(listOf(newer), store.eligible(identity))
    }

    @Test fun acknowledgementNeverRemovesAnEditedEntry() {
        val original = item("one")
        persist(original)
        val identity = store.snapshot()!!
        val updated = original.copy(message = "New fixture content")
        persist(updated)
        assertFalse(store.acknowledge(identity, original))
        assertEquals(listOf(updated), queue())
    }

    @Test fun logoutAndSameDeviceRepairNeverSendOldAccountItems() {
        val original = item("one")
        persist(original)
        val previous = store.snapshot()!!
        store.writeSecret("device_token", null)
        assertNull(store.snapshot())
        assertFalse(store.isCurrent(previous))
        assertFalse(store.acknowledge(previous, original))
        pair(token = "new-fixture-token")
        val current = store.snapshot()!!
        assertTrue(store.eligible(current).isEmpty())
        assertTrue(queue().isEmpty())
        assertTrue(prefs.getString(queueKey, null)!!.contains("one"))
        val fresh = item("two")
        persist(fresh)
        assertEquals(listOf(fresh), store.eligible(current))
        assertTrue(prefs.getString(queueKey, null)!!.contains("one"))
    }

    @Test fun interruptedRepairDoesNotBecomeEligibleOnProcessRestart() {
        persist(item("one"))
        store.writeSecret("device_token", "changed-fixture-token")
        val restarted = newStore().apply { initialize() }
        assertNull(restarted.snapshot())
        restarted.writePreference("server", "https://fixture.invalid")
        assertNotNull(restarted.snapshot())
    }

    @Test fun changingOnlyServerCannotRetargetAnExistingToken() {
        persist(item("one"))
        store.writePreference("server", "https://other.invalid")
        assertNull(store.snapshot())
    }

    @Test fun permanentErrorsKeepPayloadAndAuthorizationFailureBlocksOtherItems() {
        val first = item("one"); val second = item("two")
        persist(first, second)
        val identity = store.snapshot()!!
        store.permanentFailure(identity, first, 422, "invalid_request")
        val failedFirst = first.copy(lastError = "invalid_request")
        assertEquals(listOf(failedFirst, second), queue())
        assertEquals(listOf(second), store.eligible(identity))
        assertTrue(store.readPreference(device + NativeOutboxStore.FAILED_SUFFIX)!!.contains("invalid_request"))
        store.permanentFailure(identity, second, 401, "unauthorized")
        assertTrue(store.eligible(identity).isEmpty())
        assertEquals(listOf(failedFirst, second.copy(lastError = "unauthorized")), queue())
    }

    @Test fun failedEntriesCannotBeAuthorizedByAnAllowedListWrite() {
        val failed = item("failed").copy(lastError = "invalid_request")
        persist(failed)
        assertEquals(listOf(failed), queue())
        assertTrue(store.eligible(store.snapshot()!!).isEmpty())
    }

    @Test fun backgroundAndStaleCachesNeverRevivePermanentFailuresOrRefusals() {
        val original = item("failed")
        val refused = item("refused").copy(deliveryDeclined = true)
        val fresh = item("fresh")
        persist(original, refused, fresh)
        val identity = store.snapshot()!!
        store.permanentFailure(identity, original, 422, "invalid_request")
        // A controller that has not observed the worker failure or refusal must
        // not remove those guards just by saving an unrelated draft/allowed list.
        persist(original, refused.copy(deliveryDeclined = false), fresh)
        store.authorizeBackground()
        assertEquals(listOf(original.copy(lastError = "invalid_request"), refused, fresh), queue())
        assertEquals(listOf(fresh), store.eligible(identity))
        assertEquals(listOf("fresh"), json.decodeFromString<List<String>>(store.readPreference(allowedKey)!!))
        val restarted = newStore().apply { initialize() }
        assertEquals(listOf(fresh), restarted.eligible(restarted.snapshot()!!))
    }

    @Test fun explicitAtomicRetryCanClearFailureAndDeclinedGuards() {
        val original = item("one")
        persist(original)
        val identity = store.snapshot()!!
        store.permanentFailure(identity, original, 401, "unauthorized")
        val failed = queue().single().copy(deliveryDeclined = true)
        persist(failed)
        store.updatePreferences(listOf(queueKey, allowedKey)) { latest ->
            val entries = json.decodeFromString<List<OutboxItem>>(latest[queueKey]!!)
            mapOf(queueKey to json.encodeToString(entries.map {
                it.copy(lastError = null, deliveryDeclined = false)
            }), allowedKey to json.encodeToString(listOf(original.clientId)))
        }
        assertEquals(listOf(original), queue())
        assertEquals(listOf(original), store.eligible(identity))
        assertNull(store.readPreference(device + NativeOutboxStore.FAILED_SUFFIX))
    }

    @Test fun atomicCancellationPreservesOtherAccountsAndNewerEntries() {
        val old = item("old-account")
        persist(old)
        pair(token = "new-fixture-token")
        val refused = item("refused").copy(deliveryDeclined = true)
        val newer = item("newer")
        persist(refused, newer)
        store.updatePreferences(listOf(queueKey, allowedKey)) { latest ->
            val entries = json.decodeFromString<List<OutboxItem>>(latest[queueKey]!!)
            val allowed = json.decodeFromString<List<String>>(latest[allowedKey]!!)
            mapOf(queueKey to json.encodeToString(entries.filterNot { it.clientId == refused.clientId }),
                allowedKey to json.encodeToString(allowed.filterNot { it == refused.clientId }))
        }
        assertEquals(listOf(newer), queue())
        assertTrue(prefs.getString(queueKey, null)!!.contains(old.clientId))
        store.authorizeBackground()
        assertEquals(listOf(newer), store.eligible(store.snapshot()!!))
    }

    @Test fun oldOwnerEntriesCannotBeAcknowledgedOrBlockedByTheNewPairing() {
        val original = item("one")
        persist(original)
        val oldIdentity = store.snapshot()!!
        store.writeSecret("device_token", null)
        pair(token = "new-fixture-token")
        val current = store.snapshot()!!
        assertFalse(store.acknowledge(oldIdentity, original))
        assertFalse(store.acknowledge(current, original))
        store.permanentFailure(current, original, 401, "unauthorized")
        assertNull(store.readPreference(device + NativeOutboxStore.FAILED_SUFFIX))
        val fresh = item("two")
        persist(fresh)
        assertEquals(listOf(fresh), store.eligible(current))
        assertTrue(prefs.getString(queueKey, null)!!.contains("one"))
    }

    @Test fun staleFailureDoesNotOverwriteAnEditedPayloadOrItsPermission() {
        val original = item("one")
        persist(original)
        val identity = store.snapshot()!!
        val updated = original.copy(message = "Updated fixture")
        persist(updated)
        store.permanentFailure(identity, original, 422, "invalid_request")
        assertEquals(listOf(updated), queue())
        assertEquals(listOf(updated), store.eligible(identity))
        assertNull(store.readPreference(device + NativeOutboxStore.FAILED_SUFFIX))
    }

    @Test fun coherentLegacyCredentialsAndQueueMigrateWithoutDroppingPayload() {
        val original = item("legacy")
        persist(original)
        assertTrue(meta.edit().clear().commit())
        val migrated = newStore().apply { initialize() }
        assertEquals(listOf(original), migrated.eligible(migrated.snapshot()!!))
        assertEquals(listOf(original), queue())
    }

    @Test fun pendingCredentialMetadataNeverMigratesEvenWithAStoredToken() {
        persist(item("one"))
        assertTrue(meta.edit().clear().putBoolean("credential_pending", true).commit())
        val restarted = newStore().apply { initialize() }
        assertNull(restarted.snapshot())
        assertFalse(restarted.isCurrent(OutboxIdentity(device, "https://fixture.invalid", "fixture-token", "stale-epoch")))
        assertTrue(prefs.getString(queueKey, null)!!.contains("one"))
    }

    @Test fun initializeAfterLogoutDoesNotRestoreThePreviousCredentialEpoch() {
        persist(item("one"))
        val previous = store.snapshot()!!
        store.writeSecret("device_token", null)
        val restarted = newStore().apply { initialize() }
        assertNull(restarted.snapshot())
        assertFalse(restarted.isCurrent(previous))
        assertFalse(restarted.acknowledge(previous, item("one")))
        assertTrue(prefs.getString(queueKey, null)!!.contains("one"))
    }

    @Test fun atomicForegroundDeltaAndWorkerAcknowledgementPreserveNewerItems() {
        val first = item("one"); val newer = item("two")
        persist(first)
        val identity = store.snapshot()!!
        val executor = Executors.newFixedThreadPool(2)
        try {
            val add = executor.submit {
                store.updatePreferences(listOf(queueKey, allowedKey)) { values ->
                    val current = json.decodeFromString<List<OutboxItem>>(values[queueKey]!!)
                    val allowed = json.decodeFromString<List<String>>(values[allowedKey]!!)
                    mapOf(queueKey to json.encodeToString(current + newer), allowedKey to json.encodeToString(allowed + newer.clientId))
                }
            }
            val ack = executor.submit { store.acknowledge(identity, first) }
            add.get(5, TimeUnit.SECONDS); ack.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(newer), queue())
            assertEquals(listOf(newer), store.eligible(identity))
        } finally { executor.shutdownNow() }
    }

    @Test fun corruptionIsRetainedForForegroundRecovery() {
        assertTrue(prefs.edit().putString(queueKey, "corrupt-fixture").commit())
        assertThrows(IllegalStateException::class.java) { store.eligible(store.snapshot()!!) }
        assertEquals("corrupt-fixture", prefs.getString(queueKey, null))
    }
}
