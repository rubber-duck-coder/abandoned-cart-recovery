package com.abandonedcart.recovery.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class JsonCodec(
    val objectMapper: ObjectMapper = defaultObjectMapper(),
) {
    fun toJson(value: Any): String = objectMapper.writeValueAsString(value)

    inline fun <reified T> fromJson(json: String): T = objectMapper.readValue(json)

    companion object {
        fun defaultObjectMapper(): ObjectMapper = jacksonObjectMapper()
    }
}
