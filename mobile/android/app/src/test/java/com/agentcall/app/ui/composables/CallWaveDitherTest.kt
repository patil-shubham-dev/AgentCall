package com.agentcall.app.ui.composables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CallWaveDitherTest {

    @Test
    fun `wave field is solid at the front`() {
        assertEquals(1f, waveShape(-2f), 0f)
    }

    @Test
    fun `wave field is clear behind the crest`() {
        assertEquals(0f, waveShape(2f), 0f)
    }

    @Test
    fun `wave boundary is dithered at the midpoint`() {
        assertEquals(0.5f, waveShape(0f), 0.0001f)
    }

    @Test
    fun `ordered bayer matrix keeps the reference corners`() {
        assertEquals(0f, BAYER8[0], 0f)
        assertEquals(63f / 64f, BAYER8[56], 0f)
    }

    @Test
    fun `full and empty shape resolve with the bayer threshold`() {
        assertTrue(waveShape(-1.5f) + BAYER8[0] >= 1f)
        assertFalse(waveShape(1.5f) + BAYER8[63] >= 1f)
    }

    @Test
    fun `block coordinates wrap like glsl mod`() {
        assertEquals(0, mod8(8))
        assertEquals(7, mod8(-1))
        assertEquals(0, mod8(-8))
        assertEquals(4, mod8(-12))
    }

    @Test
    fun `silence stays calm but never flat`() {
        val silence = waveSpread(0f, 1f)
        val loud = waveSpread(1f, 1f)
        assertTrue(silence > 0f)
        assertTrue(loud > silence * 4f)
        assertTrue(organicWave(0.4f, 1f, 0f, 0f, silence) != organicWave(0.4f, 2f, 0f, 0f, silence))
    }

    @Test
    fun `loud voice raises organic displacement`() {
        val x = 0.8f
        val t = 3.1f
        val quiet = abs(organicWave(x, t, -0.7f, 0f, waveSpread(0f, 1f)))
        val loud = abs(organicWave(x, t, -0.7f, 1f, waveSpread(1f, 1f)))
        assertTrue(loud > quiet * 2f)
    }

    @Test
    fun `envelope attacks faster than it releases`() {
        val attack = smoothLevel(0f, 1f, 0.1f) - 0f
        val release = 1f - smoothLevel(1f, 0f, 0.1f)
        assertTrue(attack > release * 2f)
        assertEquals(1f, smoothLevel(1f, 1f, 0.1f), 0f)
    }

    @Test
    fun `collision only triggers when fronts are close`() {
        assertEquals(0f, collisionWeight(0.28f), 0f)
        assertEquals(0f, collisionWeight(0.4f), 0f)
        assertEquals(1f, collisionWeight(0f), 0f)
        assertTrue(collisionWeight(0.05f) > collisionWeight(0.12f))
    }
}
