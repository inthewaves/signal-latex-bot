package signallatexbot

object Hex {
    private const val HEX_CHARS = "0123456789abcdef"

    /** Encodes a byte array to hex.  */
    fun encode(bytes: ByteArray): String {
        val result = StringBuilder(2 * bytes.size)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xff
            result.append(HEX_CHARS[unsigned / 16])
            result.append(HEX_CHARS[unsigned % 16])
        }
        return result.toString()
    }

    /** Decodes a hex string to a byte array.  */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "bad string length" }
        val size = hex.length / 2
        val result = ByteArray(size)
        for (i in 0 until size) {
            val hi = hex[2 * i].digitToIntOrNull(16) ?: error("non-hex input")
            val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: error("non-hex input")
            result[i] = (16 * hi + lo).toByte()
        }
        return result
    }
}