package com.example.thermohammer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {

    @Test
    fun `the manufacturer is put in front once, never twice`() {
        assertEquals("HONOR 600 Pro", deviceDisplayName("HONOR", "HONOR 600 Pro"))          // stored with the manufacturer already
        assertEquals("HONOR VKP-NX9", deviceDisplayName("HONOR", "HONOR VKP-NX9"))
        assertEquals("Google Pixel 9", deviceDisplayName("Google", "Google Pixel 9"))
        assertEquals("honor 600 Pro", deviceDisplayName("HONOR", "honor 600 Pro"))           // any case
    }

    @Test
    fun `a model without the manufacturer gets it`() {
        assertEquals("Apple iPhone 15 Pro", deviceDisplayName("Apple", "iPhone 15 Pro"))
        assertEquals("HONOR 90", deviceDisplayName("HONOR", "90"))                          // older stress rows were stored without it
    }

    @Test
    fun `no manufacturer leaves the model alone`() {
        assertEquals("Pixel 9", deviceDisplayName("", "Pixel 9"))
        assertEquals("Pixel 9", deviceDisplayName("  ", "Pixel 9"))
    }
}
