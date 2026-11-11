package me.waveio.blinkchat

import kotlinx.serialization.json.*

/** Fixed, bounded tools; provider output never becomes an OS command. */
object ChatTools {
    val definitions: List<JsonObject> = listOf(
        tool("generate_image", "Create an image from a detailed prompt and show it in this conversation.", mapOf("prompt" to "Image description"), listOf("prompt")),
        tool("current_time", "Read the current date and time on this device."),
        tool("list_files", "List text files in the app's private workspace."),
        tool("read_file", "Read a text file in the app's private workspace.", mapOf("path" to "Relative file path"), listOf("path")),
        tool("write_file", "Create or update a text file in the app's private workspace.", mapOf("path" to "Relative file path", "content" to "Complete UTF-8 file content"), listOf("path", "content")),
    )

    private fun tool(name: String, description: String, fields: Map<String, String> = emptyMap(), required: List<String> = emptyList()) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name); put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") { fields.forEach { (key, help) -> putJsonObject(key) { put("type", "string"); put("description", help) } } }
                put("required", JsonArray(required.map(::JsonPrimitive)))
                put("additionalProperties", false)
            }
        }
    }
}
