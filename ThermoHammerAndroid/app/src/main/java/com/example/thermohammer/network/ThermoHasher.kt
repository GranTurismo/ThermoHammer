package com.example.thermohammer.network

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Matches the iOS ThermoHasher.computeHash() implementation exactly.
 *
 * baseize() encodes each Unicode scalar as 4 bytes (UTF-32 LE) and Base64 encodes the result.
 * computeHash() concatenates all baseized fields, then HMAC-SHA256s with the encryption key.
 */
object ThermoHasher {

    /**
     * Encodes a string the same way as the iOS baseize() function:
     * Each Unicode scalar → 4 bytes (little-endian) → Base64
     */
    private fun baseize(txt: String): String {
        val bytes = mutableListOf<Byte>()
        for (scalar in txt) {
            val v = scalar.code
            bytes.add((v and 0xFF).toByte())
            bytes.add(((v shr 8) and 0xFF).toByte())
            bytes.add(((v shr 16) and 0xFF).toByte())
            bytes.add(((v shr 24) and 0xFF).toByte())
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    fun computeHash(encryptionKey: String, stamps: List<DeviceHammerStamp>): String {
        val sb = StringBuilder()
        for (stamp in stamps) {
            val thermalStr = when (stamp.thermalState) {
                0 -> "Nominal"
                1 -> "Fair"
                2 -> "Serious"
                3 -> "Critical"
                else -> "Nominal"
            }
            sb.append(baseize(stamp.elapsedMs.toString()))
            sb.append(baseize(stamp.score.toString()))
            sb.append(baseize(thermalStr))
        }

        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val msgBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val signature = mac.doFinal(msgBytes)
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }
}
