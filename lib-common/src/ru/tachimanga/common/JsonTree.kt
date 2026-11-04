package ru.tachimanga.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Small helpers for reading JSON by hand. Tachimanga lacks some of the serializers that
// generated code needs (booleans, floats, JsonElement fields), so sources that hit those
// read the JSON tree directly with these.

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

val JsonElement?.list: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.content

val JsonElement?.int: Int? get() = str?.toDoubleOrNull()?.toInt()

val JsonElement?.long: Long? get() = str?.toDoubleOrNull()?.toLong()

val JsonElement?.double: Double? get() = str?.toDoubleOrNull()

val JsonElement?.bool: Boolean? get() = when (str) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}
