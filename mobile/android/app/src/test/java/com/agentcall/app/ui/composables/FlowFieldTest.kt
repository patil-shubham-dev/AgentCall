package com.agentcall.app.ui.composables

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlowFieldTest {

    @Test
    fun `spawn positions stay in bounds`() {
        for (i in 0 until 500) {
            assertTrue(spawnX(i) in 0f..1f)
            assertTrue(spawnY(i) in 0f..1f)
        }
    }

    @Test
    fun `spawn positions cover every quadrant and the centre`() {
        var centreHits = 0
        val quadrants = BooleanArray(4)
        for (i in 0 until 400) {
            val x = spawnX(i)
            val y = spawnY(i)
            if (x in 0.25f..0.75f && y in 0.25f..0.75f) centreHits++
            val quadrant = (if (y < 0.5f) 0 else 2) + if (x < 0.5f) 0 else 1
            quadrants[quadrant] = true
        }
        assertTrue(quadrants.all { it })
        assertTrue(centreHits > 100)
    }

    @Test
    fun `flow routes differ across the viewport and over time`() {
        val centre = flowAngle(180f, 400f, 360f, 800f, tick = 0f)
        val edge = flowAngle(20f, 40f, 360f, 800f, tick = 0f)
        val later = flowAngle(180f, 400f, 360f, 800f, tick = 600f)
        assertTrue(abs(centre - edge) > 0.01f)
        assertTrue(abs(centre - later) > 0.01f)
        assertTrue(centre.isFinite() && edge.isFinite() && later.isFinite())
    }
}
