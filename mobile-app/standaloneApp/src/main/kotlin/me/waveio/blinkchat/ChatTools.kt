package me.waveio.blinkchat

import kotlinx.serialization.json.*

/** Fixed, bounded tools; provider output never becomes an OS command. */
object ChatTools {
    val definitions: List<JsonObject> = listOf(
        tool("generate_image", "Create an image from a detailed prompt and show it in this conversation.", mapOf("prompt" to "Image description"), listOf("prompt")),
        tool("current_time", "Read the current date and time on this device."),
        tool("web_search", "Search the public web and return result titles, URLs and short snippets.", mapOf("query" to "Search query"), listOf("query")),
        tool("read_url", "Open a public web page and return its readable text.", mapOf("url" to "HTTPS page URL"), listOf("url")),
        tool("remember", "Save a durable fact about the user to persistent memory. ONLY use it when the user explicitly asks to remember something, with phrases like \"remember that...\" or \"don't forget...\". To update an existing memory, send its new full text.", mapOf("fact" to "One-sentence fact about the user, first person"), listOf("fact")),
        tool("forget", "Delete a matching memory when the user explicitly asks to forget or remove it.", mapOf("fact" to "Text matching the memory to delete"), listOf("fact")),
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
