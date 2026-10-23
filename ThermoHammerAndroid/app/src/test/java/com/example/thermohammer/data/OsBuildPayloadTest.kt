package com.example.thermohammer.data

import com.example.thermohammer.network.DeviceHammerStamp
import com.example.thermohammer.network.HammerPayload
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OS build identity must survive the offline path (test → save → update phone → upload)
 * and must not break results that older app versions already saved on the user's device.
 */
class OsBuildPayloadTest {

    private val gson = Gson()

    private fun payload(osBuild: String?, patch: String?) = HammerPayload(
        stamps = listOf(DeviceHammerStamp(1000, 5L, 0)),
        type = 0, deviceManufacturer = "Google", deviceModel = "Google Pixel 9 Pro", os = 2,
        osVersion = "16", sessionId = 1, hash = "h", osBuild = osBuild, securityPatch = patch
    )

    @Test fun payloadCarriesOsBuildAndPatchUnderTheNamesTheServerExpects() {
        val json = JsonParser.parseString(gson.toJson(payload("BP4A.251205.006", "2026-10-05"))).asJsonObject
        assertEquals("BP4A.251205.006", json.get("osBuild").asString)
        assertEquals("2026-10-05", json.get("securityPatch").asString)
    }

    @Test fun absentValuesAreOmittedNotSentAsEmpty() {
        val json = JsonParser.parseString(gson.toJson(payload(null, null))).asJsonObject
        assertFalse(json.has("osBuild"))
        assertFalse(json.has("securityPatch"))
    }

    @Test fun resultSavedByAnOlderAppVersionStillLoadsWithNullBuild() {
        // A pending result as written before osBuild / securityPatch existed.
        val legacy = """
            [{"id":"a1","timestamp":1,"durationSeconds":300,"testDurationType":0,"testThreadingType":1,
              "minStability":80.0,"finalStability":90.0,"worstThermalState":1,"stamps":[],
              "deviceModel":"Google Pixel 9 Pro","deviceManufacturer":"Google","osVersion":"15",
              "sessionId":0,"encryptionKey":"","schemaVersion":2,"validityFlags":0}]
        """.trimIndent()
        val type = object : TypeToken<List<PendingTestResult>>() {}.type
        val loaded: List<PendingTestResult> = gson.fromJson(legacy, type)

        assertEquals(1, loaded.size)
        assertEquals("15", loaded[0].osVersion)
        assertNull(loaded[0].osBuild)          // unknown — must never be filled in with today's build
        assertNull(loaded[0].securityPatch)
    }

    @Test fun buildRecordedAtTestTimeRoundTripsThroughTheStoreFormat() {
        val saved = PendingTestResult(
            id = "x", timestamp = 1, durationSeconds = 300, testDurationType = 0,
            minStability = 0f, finalStability = 0f, worstThermalState = 0, stamps = emptyList(),
            deviceModel = "m", deviceManufacturer = "g", osVersion = "15", sessionId = 0, encryptionKey = "",
            osBuild = "AP4A.250105.002", securityPatch = "2025-01-05"
        )
        val type = object : TypeToken<List<PendingTestResult>>() {}.type
        val back: List<PendingTestResult> = gson.fromJson(gson.toJson(listOf(saved)), type)

        assertEquals("AP4A.250105.002", back[0].osBuild)
        assertEquals("2025-01-05", back[0].securityPatch)
        assertTrue("an upload after a phone update must report the OLD build",
            back[0].osBuild != "BP4A.251205.006")
    }
}
