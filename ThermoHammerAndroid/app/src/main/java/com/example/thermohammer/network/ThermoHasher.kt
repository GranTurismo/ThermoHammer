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

    private fun thermalName(thermalState: Int): String = when (thermalState) {
        0 -> "Nominal"
        1 -> "Fair"
        2 -> "Serious"
        3 -> "Critical"
        else -> "Nominal"
    }

    private fun hmacSha256(encryptionKey: String, message: String): String {
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val signature = mac.doFinal(msgBytes)
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /** Legacy wire-stamp hash — the exact scheme the server verifies. */
    fun computeHash(encryptionKey: String, stamps: List<DeviceHammerStamp>): String {
        val sb = StringBuilder()
        for (stamp in stamps) {
            sb.append(baseize(stamp.elapsedMs.toString()))
            sb.append(baseize(stamp.score.toString()))
            sb.append(baseize(thermalName(stamp.thermalState)))
        }
        return hmacSha256(encryptionKey, sb.toString())
    }

    /**
     * v2 hash — covers the whole submission: run metadata + every stamp, so
     * conditions/identity fields cannot be altered without detection.
     * Canonical meta form: "v2|type|threading|manufacturer|model|osVersion|
     *                       baselineScore|deliveredCapacity|validityFlags"
     */
    fun computeHashV2(
        encryptionKey: String,
        metaCanonical: String,
        stamps: List<DeviceHammerStamp>
    ): String {
        val sb = StringBuilder()
        sb.append(baseize(metaCanonical))
        for (stamp in stamps) {
            sb.append(baseize(stamp.elapsedMs.toString()))
            sb.append(baseize(stamp.score.toString()))
            sb.append(baseize(thermalName(stamp.thermalState)))
        }
        return hmacSha256(encryptionKey, sb.toString())
    }
}
