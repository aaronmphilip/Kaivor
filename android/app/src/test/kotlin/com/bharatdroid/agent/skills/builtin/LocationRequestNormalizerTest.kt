package com.bharatdroid.agent.skills.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRequestNormalizerTest {

    @Test
    fun `normalizePickup clears gps style pickup phrases`() {
        assertEquals("", LocationRequestNormalizer.normalizePickup("use my location"))
        assertEquals("", LocationRequestNormalizer.normalizePickup("right now location"))
    }

    @Test
    fun `wantsCurrentLocation recognizes current location phrases`() {
        assertTrue(LocationRequestNormalizer.wantsCurrentLocation("current location"))
        assertTrue(LocationRequestNormalizer.wantsCurrentLocation("pickup using gps"))
    }
}
