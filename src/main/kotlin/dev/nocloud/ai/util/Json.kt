package dev.nocloud.ai.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Single JSON codec used across the plugin. */
object Json {

    val instance: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = false
    }
}

/** Parses [text] as a JSON object, returning an empty object when it is not valid JSON. */
fun parseJsonObject(text: String): JsonObject = runCatching {
    Json.instance.parseToJsonElement(text).jsonObject
}.getOrDefault(JsonObject(emptyMap()))

/** Parses [text] as any JSON element, returning null when it is not valid JSON. */
fun parseJsonElement(text: String): JsonElement? = runCatching {
    Json.instance.parseToJsonElement(text)
}.getOrNull()

/** Renders a JSON element compactly. */
fun JsonElement.toJsonString(): String = Json.instance.encodeToString(JsonElement.serializer(), this)
