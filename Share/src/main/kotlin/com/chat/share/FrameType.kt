package com.chat.share

enum class FrameType(val code: Byte) {
    JSON_PACKET(0x01),
    FILE_CHUNK(0x02),
    FILE_CONTROL(0x03),
    HEARTBEAT(0x04);

    companion object {
        fun fromCode(code: Byte): FrameType? =
            entries.find { it.code == code}
    }
}