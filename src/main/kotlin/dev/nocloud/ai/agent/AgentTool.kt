package dev.nocloud.ai.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A capability the model can invoke.
 *
 * Implementations must never throw: a failure is reported as text so the model can
 * react to it instead of the run dying.
 */
interface AgentTool {
    val name: String
    val description: String

    /** JSON Schema for the tool arguments, in OpenAI function-tool shape. */
    val parameters: JsonObject

    suspend fun execute(arguments: String): String
}

/** Small helpers for declaring JSON Schema objects without hand-writing JSON strings. */
object Schema {

    fun string(description: String, enumValues: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enumValues.isNotEmpty()) {
            put("enum", buildJsonArray { enumValues.forEach { add(JsonPrimitive(it)) } })
        }
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun number(description: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = properties.keys.toList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (key, value) -> put(key, value) } })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

/** Reads a string field from a tool argument payload. */
fun JsonObject.stringArg(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotEmpty() }

fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

fun JsonObject.boolArg(name: String, default: Boolean = false): Boolean =
    (this[name] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: default
