package com.chat.share

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object JsonUtil {
    val mapper: ObjectMapper = ObjectMapper().registerModule(
        KotlinModule.Builder()
            .configure(KotlinFeature.NullIsSameAsDefault, true)
            .configure(KotlinFeature.NullToEmptyCollection, true)
            .configure(KotlinFeature.NullToEmptyMap, true)
            .configure(KotlinFeature.StrictNullChecks, true)
            .build()
    )

    /*
     * inline  : 함수 호출 시 코드 복사 -> 컴파일 시 타입 정보 보존
     * reified : Generic 타입 T를 런타임에 "구체화"시킴
     */
    inline fun <reified T> toJsonBytes(data: T): ByteArray =
        mapper.writeValueAsBytes(data)

//    inline fun <reified T> fromJsonBytes(bytes: ByteArray): T =
//        mapper.readValue(bytes)
}
