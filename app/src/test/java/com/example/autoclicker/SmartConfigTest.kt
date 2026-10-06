package com.example.autoclicker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.autoclicker.data.SmartConfig
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SmartConfigMode
import com.example.autoclicker.data.SmartRule
import com.example.autoclicker.engine.CustomRuleSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartConfigTest {

    private lateinit var context: Context
    private lateinit var manager: SmartConfigManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SmartConfigManager.resetForTesting()
        manager = SmartConfigManager.getInstance(context)
        manager.setMode(SmartConfigMode.DEFAULT_ONLY)
        manager.setActiveName("")
    }

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
            assertNotNull(rule.region)
        }
    }

    @Test
    fun testJsonParsingValidConfig() {
        val json = """
            {
              "name": "CustomPUBG",
              "version": 1,
              "refWidth": 1340,
              "refHeight": 800,
              "scanIntervalMs": 1500,
              "idleTimeoutMin": 10,
              "rules": [
                {
                  "id": "start",
                  "priority": 1,
                  "image": { "file": "start_btn.png" },
                  "region": [0.0, 0.4, 0.5, 1.0],
                  "threshold": 0.85,
                  "tap": { "target": "found" },
                  "afterDelayMs": 5000
                },
                {
                  "id": "custom_skip",
                  "priority": 2,
                  "image": { "source": "screen.png", "box": [0.1, 0.2, 0.3, 0.4] },
                  "threshold": 0.80,
                  "tap": { "target": "fixed", "x": 0.25, "y": 0.35 },
                  "afterDelayMs": 1000
                }
              ]
            }
        """.trimIndent()

        val config = SmartConfig.fromJson(json)
        assertEquals("CustomPUBG", config.name)
        assertEquals(1500L, config.scanIntervalMs)
        assertEquals(10, config.idleTimeoutMin)
        assertEquals(2, config.rules.size)

        val r1 = config.rules[0]
        assertEquals("start", r1.id)
        assertEquals(1, r1.priority)
        assertEquals("start_btn.png", r1.imageFile)
        assertEquals("found", r1.tapTarget)
        assertFalse(r1.builtin)

        val r2 = config.rules[1]
        assertEquals("custom_skip", r2.id)
        assertEquals("screen.png", r2.imageSource)
        assertNotNull(r2.imageBox)
        assertEquals("fixed", r2.tapTarget)
        assertEquals(0.25f, r2.tapX!!, 0.001f)
        assertEquals(0.35f, r2.tapY!!, 0.001f)

        // Effective region for source+box without explicit region should expand by 25%
        val effReg = r2.getEffectiveRegion()
        assertEquals(4, effReg.size)
        // width = 0.2, height = 0.2, pad = 0.05
        assertEquals(0.05f, effReg[0], 0.001f)
        assertEquals(0.15f, effReg[1], 0.001f)
        assertEquals(0.35f, effReg[2], 0.001f)
        assertEquals(0.45f, effReg[3], 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testJsonParsingInvalidBoxReversed() {
        val json = """
            {
              "name": "Invalid",
              "rules": [
                {
                  "id": "r1",
                  "image": { "source": "s.png", "box": [0.8, 0.2, 0.3, 0.4] },
                  "tap": { "target": "found" }
                }
              ]
            }
        """.trimIndent()
        SmartConfig.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testJsonParsingFractionOutOfBounds() {
        val json = """
            {
              "name": "Invalid",
              "rules": [
                {
                  "id": "r1",
                  "image": { "file": "f.png" },
                  "tap": { "target": "fixed", "x": 1.5, "y": 0.5 }
                }
              ]
            }
        """.trimIndent()
        SmartConfig.fromJson(json)
    }

    @Test
    fun testBuildEffectiveRulesModes() {
        // Prepare dummy custom config in filesDir/configs/test_cfg
        val configsDir = File(context.filesDir, "configs")
        val testDir = File(configsDir, "test_cfg")
        testDir.mkdirs()

        val json = """
            {
              "name": "test_cfg",
              "version": 1,
              "refWidth": 1340,
              "refHeight": 800,
              "scanIntervalMs": 1200,
              "idleTimeoutMin": 7,
              "rules": [
                {
                  "id": "start",
                  "priority": 1,
                  "image": { "file": "custom_start.png" },
                  "tap": { "target": "found" },
                  "afterDelayMs": 4000
                },
                {
                  "id": "new_reward",
                  "priority": 2,
                  "image": { "file": "reward.png" },
                  "tap": { "target": "found" },
                  "afterDelayMs": 1000
                }
              ]
            }
        """.trimIndent()
        File(testDir, "config.json").writeText(json, Charsets.UTF_8)
        File(testDir, "custom_start.png").writeBytes(byteArrayOf(1, 2, 3))
        File(testDir, "reward.png").writeBytes(byteArrayOf(1, 2, 3))

        // 1. DEFAULT_ONLY mode
        manager.setMode(SmartConfigMode.DEFAULT_ONLY)
        manager.setActiveName("test_cfg")
        val custom = manager.loadCustomConfig("test_cfg")
        assertNotNull("Custom config should not be null", custom)
        val effDefault = manager.buildEffectiveRules()
        assertEquals(SmartConfigMode.DEFAULT_ONLY, effDefault.mode)
        assertEquals(3, effDefault.rules.size)
        assertEquals(3, effDefault.builtinCount)
        assertEquals(0, effDefault.customCount)
        assertTrue(effDefault.rules.all { it.builtin })

        // 2. CUSTOM_ONLY mode
        manager.setMode(SmartConfigMode.CUSTOM_ONLY)
        val effCustom = manager.buildEffectiveRules()
        assertEquals(SmartConfigMode.CUSTOM_ONLY, effCustom.mode)
        assertEquals(2, effCustom.rules.size)
        assertEquals(0, effCustom.builtinCount)
        assertEquals(2, effCustom.customCount)
        assertEquals(1200L, effCustom.scanIntervalMs)
        assertEquals(7, effCustom.idleTimeoutMin)
        assertFalse(effCustom.rules.any { it.builtin })

        // 3. MERGED mode
        // In MERGED mode: "start" replaces builtin "start".
        // "continue_mvp" and "continue_blue" are retained from default.
        // "new_reward" is added.
        manager.setMode(SmartConfigMode.MERGED)
        val effMerged = manager.buildEffectiveRules()
        assertEquals(SmartConfigMode.MERGED, effMerged.mode)
        assertEquals(4, effMerged.rules.size)
        // Check "start" is replaced with custom rule
        val startRule = effMerged.rules.first { it.id == "start" }
        assertFalse("Custom start rule must replace builtin", startRule.builtin)
        assertEquals(1, startRule.priority)
        assertEquals(4000L, startRule.afterDelayMs)

        // 4. Fallback to DEFAULT_ONLY if no custom config active
        manager.setActiveName("")
        val effFallback = manager.buildEffectiveRules()
        assertEquals(SmartConfigMode.DEFAULT_ONLY, effFallback.mode)
        assertEquals(3, effFallback.rules.size)
        assertEquals(3, effFallback.builtinCount)

        // Clean up
        testDir.deleteRecursively()
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

        // Монотонно растут
        for (i in 0 until list.size - 1) {
            assertTrue("Значения должны монотонно расти", list[i] < list[i + 1])
        }

        // Отношение соседних постоянно
        val expectedRatio = (max / min).toDouble().pow(1.0 / 8.0)
        for (i in 0 until list.size - 1) {
            val actualRatio = (list[i + 1] / list[i]).toDouble()
            assertEquals(expectedRatio, actualRatio, 0.01)
        }
    }

    @Test
    fun testScaleRangeValidation() {
        // min < 0.2 отклоняется
        try {
            val jsonMin = """
                {
                  "name": "InvalidMin",
                  "rules": [
                    {
                      "id": "r1",
                      "image": { "file": "f.png", "scaleRange": [0.1, 1.0] },
                      "tap": { "target": "found" }
                    }
                  ]
                }
            """.trimIndent()
            SmartConfig.fromJson(jsonMin)
            org.junit.Assert.fail("0.1 scaleRange min must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("scaleRange") == true)
        }

        // max > 3.0 отклоняется
        try {
            val jsonMax = """
                {
                  "name": "InvalidMax",
                  "rules": [
                    {
                      "id": "r1",
                      "image": { "file": "f.png", "scaleRange": [1.0, 3.5] },
                      "tap": { "target": "found" }
                    }
                  ]
                }
            """.trimIndent()
            SmartConfig.fromJson(jsonMax)
            org.junit.Assert.fail("3.5 scaleRange max must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("scaleRange") == true)
        }
    }

    private fun createZip(zipFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun testImportZipWithDifferentExtensions() {
        val zipFile = File(context.cacheDir, "test_exts.zip")
        val json = """
            {
              "name": "ExtConfig",
              "rules": [
                { "id": "r1", "image": { "file": "test.webp" }, "tap": { "target": "found" } },
                { "id": "r2", "image": { "file": "test.bmp" }, "tap": { "target": "found" } },
                { "id": "r3", "image": { "file": "test.gif" }, "tap": { "target": "found" } },
                { "id": "r4", "image": { "file": "test.heic" }, "tap": { "target": "found" } }
              ]
            }
        """.trimIndent()

        createZip(zipFile, mapOf(
            "config.json" to json.toByteArray(Charsets.UTF_8),
            "test.webp" to byteArrayOf(1, 2, 3),
            "test.bmp" to byteArrayOf(1, 2, 3),
            "test.gif" to byteArrayOf(1, 2, 3),
            "test.heic" to byteArrayOf(1, 2, 3)
        ))

        val result = manager.importZipWithReport(Uri.fromFile(zipFile))
        assertTrue("Импорт должен быть успешным даже при нечитаемых изображениях", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals("ExtConfig", report.configName)
        assertEquals(4, report.rulesCount)
        assertTrue("Должны быть проблемы декодирования нечитаемых картинок", report.problems.isNotEmpty())

        zipFile.delete()
    }

    @Test
    fun testImportZipSubfolder() {
        val zipFile = File(context.cacheDir, "test_subfolder.zip")
        val json = """
            {
              "name": "SubfolderConfig",
              "rules": [
                { "id": "r1", "image": { "file": "btn.png" }, "tap": { "target": "found" } }
              ]
            }
        """.trimIndent()

        createZip(zipFile, mapOf(
            "my_pack/config.json" to json.toByteArray(Charsets.UTF_8),
            "my_pack/btn.png" to byteArrayOf(1, 2, 3)
        ))

        val result = manager.importZipWithReport(Uri.fromFile(zipFile))
        assertTrue("Импорт из единственной подпапки должен пройти успешно", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals("SubfolderConfig", report.configName)
        assertEquals(1, report.rulesCount)

        zipFile.delete()
    }

    @Test
    fun testImportZipWithTrailingText() {
        val zipFile = File(context.cacheDir, "test_trailing.zip")
        val json = """
            {
              "name": "TrailingConfig",
              "rules": [
                { "id": "r1", "image": { "file": "btn.png" }, "tap": { "target": "found" } }
              ]
            }
            Файлы для zip: btn.png
        """.trimIndent()

        createZip(zipFile, mapOf(
            "config.json" to json.toByteArray(Charsets.UTF_8),
            "btn.png" to byteArrayOf(1, 2, 3)
        ))

        val result = manager.importZipWithReport(Uri.fromFile(zipFile))
        assertTrue("Импорт с посторонним текстом должен быть успешен", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals("TrailingConfig", report.configName)
        assertTrue(report.warnings.any { it.contains("посторонний текст") })

        zipFile.delete()
    }
}
