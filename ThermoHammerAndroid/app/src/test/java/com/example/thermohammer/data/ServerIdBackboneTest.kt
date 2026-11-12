package com.example.thermohammer.data

import com.example.thermohammer.network.runIdHeader
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's run id and the stress curve: kept with every run, and older saved files still load without them. */
class ServerIdBackboneTest {

    private val gson = Gson()

    @Test fun runIdHeaderIsAPositiveIntegerOrNothing() {
        assertEquals(1234, runIdHeader("1234"))
        assertEquals(7, runIdHeader(" 7 "))
        assertNull(runIdHeader(null))                         // an older server sends no header
        assertNull(runIdHeader(""))
        assertNull(runIdHeader("abc"))
        assertNull(runIdHeader("0"))
        assertNull(runIdHeader("-3"))
    }

    @Test fun benchmarkHistoryFromBeforeServerIdsStillLoads() {
        val legacy = """[{"id":"a","timestamp":1,"version":4,"score":30000,"test1Fps":1.0,"test1LowFps":1.0,"test1Points":1,
            "test2Fps":1.0,"test2LowFps":1.0,"test2Points":1,"gpuName":"Adreno","validityFlags":0,"submitted":true,"osVersion":"16"}]"""
        val loaded = gson.fromJson(legacy, Array<BenchmarkRecord>::class.java).toList()
        assertEquals(1, loaded.size)
        assertNull(loaded[0].serverId)
        assertNull(loaded[0].deviceModel)
        assertTrue(loaded[0].submitted)
    }

    @Test fun serverIdRoundTrips() {
        val r = gson.fromJson(gson.toJson(arrayOf(record().copy(serverId = 42))), Array<BenchmarkRecord>::class.java)[0]
        assertEquals(42, r.serverId)
    }

    @Test fun runHistoryFromBeforeCurvesStillLoads() {
        val legacy = """[{"id":"r","timestamp":1,"deviceModel":"Google Pixel 9 Pro","osVersion":"16","durationType":0,
            "threadingType":1,"capacity":88.5}]"""
        val r = gson.fromJson(legacy, Array<RunRecord>::class.java)[0]
        assertNull(r.serverId)
        assertNull(r.curve)
        assertEquals(88.5, r.capacity, 0.0)
    }

    @Test fun curveAndServerIdRoundTrip() {
        val run = RunRecord(id = "r", timestamp = 1, deviceModel = "M", osVersion = "16", durationType = 0, threadingType = 1,
            capacity = 90.0, serverId = 9, curve = listOf(100.0, 95.5, 80.1))
        val back = gson.fromJson(gson.toJson(arrayOf(run)), Array<RunRecord>::class.java)[0]
        assertEquals(9, back.serverId)
        assertEquals(listOf(100.0, 95.5, 80.1), back.curve)
    }

    @Test fun completeMeansEverySectionScored() {
        assertTrue(record().isComplete)
        assertEquals(false, record().copy(llmScore = 0).isComplete)
    }

    companion object {
        fun record(total: Int = 36000) = BenchmarkRecord(id = "x", timestamp = 1, version = 4, score = total, test1Fps = 1.0,
            test1LowFps = 1.0, test1Points = 1, test2Fps = 1.0, test2LowFps = 1.0, test2Points = 1, gpuScore = 6000, uxScore = 6000,
            cpuScore = 6000, aiScore = 6000, memScore = 6000, llmScore = 6000, gpuName = "Adreno", validityFlags = 0)
    }
}
