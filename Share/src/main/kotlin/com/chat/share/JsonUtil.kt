package com.chat.share

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object JsonUtil {
    val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
        .registerKotlinModule()

    // DTO -> JSON ByteArray (Send)
    inline fun <reified T> serializeToJsonBytes(obj: T): ByteArray =
        mapper.writeValueAsBytes(obj)

    // Json ByteArray -> DTO (Receive)
    inline fun <reified T> deserializeFromJsonBytes(bytes: ByteArray): T =
        try {
            mapper.readValue(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "JSON deserialization failed for type=${T::class}: ",
                e
            )
        }
}
