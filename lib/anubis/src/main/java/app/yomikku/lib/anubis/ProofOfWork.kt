package app.yomikku.lib.anubis

import java.security.MessageDigest

/** Anubis' "fast" and "slow" checks. Kept apart from the interceptor so it runs without OkHttp. */
internal object ProofOfWork {
    /** The first nonce for which sha256(data + nonce), in hex, starts with [difficulty] zeros. */
    fun solve(data: String, difficulty: Int): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = data.toByteArray()
        var nonce = 0L
        while (true) {
            digest.update(prefix)
            val hash = digest.digest(nonce.toString().toByteArray())
            if (leadingZeroNibbles(hash) >= difficulty) {
                return nonce to hash.joinToString("") { "%02x".format(it) }
            }
            nonce++
        }
    }

    private fun leadingZeroNibbles(hash: ByteArray): Int {
        var count = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                count += 2
                continue
            }
            if (value < 0x10) count++
            break
        }
        return count
    }
}
