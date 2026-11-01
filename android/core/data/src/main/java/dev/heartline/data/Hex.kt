package dev.heartline.data

object Hex {
    fun isValidAuthKey(hex: String): Boolean = hex.length == 32 && hex.all {
        it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
    }
    fun authKey(hex: String): ByteArray {
        require(isValidAuthKey(hex)) { "Watch auth key must contain exactly 32 ASCII hex characters" }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        bytes.forEach { b -> append(digits[(b.toInt() and 255) ushr 4]); append(digits[b.toInt() and 15]) }
    }
}
