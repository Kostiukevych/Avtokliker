package com.example.autoclicker

import com.example.autoclicker.data.SmartConfig
import com.example.autoclicker.engine.CustomRuleSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartConfigTest {

    @Test
    fun testDefaultConfigRules() {
        val defaultConfig = SmartConfig.createDefaultConfig()
        assertEquals("default", defaultConfig.name)
        assertEquals(1, defaultConfig.version)
        assertEquals(800, defaultConfig.refHeight)
        assertEquals(3, defaultConfig.rules.size)

        val ids = defaultConfig.rules.map { it.id }
        assertEquals(listOf("continue_mvp", "continue_blue", "start"), ids)

        for (rule in defaultConfig.rules) {
            assertTrue(rule.builtin)
            assertEquals(800, rule.refHeight)
        }
    }

    @Test
    fun testAfterDelayFloorIs50() {
        val json = """
            {
              "name": "DelayTest",
              "rules": [
                {
                  "id": "r1",
                  "image": { "file": "f.png" },
                  "tap": { "target": "found" },
                  "afterDelayMs": 10
                }
              ]
            }
        """.trimIndent()
        val config = SmartConfig.fromJson(json)
        assertEquals(50L, config.rules[0].afterDelayMs)
    }

    @Test
    fun testScaleList() {
        val base = 1.0f
        val min = 0.5f
        val max = 2.0f
        val list = CustomRuleSearch.scaleList(base, min, max, 9)
        assertEquals(9, list.size)
        assertEquals(base * min, list.first(), 0.001f)
        assertEquals(base * max, list.last(), 0.001f)
        for (i in 0 until list.size - 1) {
            assertTrue(list[i] < list[i + 1])
        }
        val expectedRatio = (max / min).toDouble().pow(1.0 / 8.0)
        for (i in 0 until list.size - 1) {
            val actualRatio = (list[i + 1] / list[i]).toDouble()
            assertEquals(expectedRatio, actualRatio, 0.01)
        }
    }
}
