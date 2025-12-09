package com.chat.share

/**
 * Represents a single binary chunk of a file being transferred.
 *
 * @property length valid bytes count in 'chunkData'.
 * @property chunkData raw binary data
 */
data class FileChunk(
    val transferId: String,
    val seq: Int,
    val length: Int,
    val chunkData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FileChunk

        if (length != other.length) return false
        if (!chunkData.contentEquals(other.chunkData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + chunkData.contentHashCode()
        return result
    }
}

data class FileControl (
    val transferId: String,
    val senderId: String,
    val receiverId: String,
    val fileName: String,
    val totalSize: Long,
    val command: FileControlCommand
)

enum class FileControlCommand {
    START, COMPLETE, CANCEL
}