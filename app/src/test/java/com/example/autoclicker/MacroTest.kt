package com.example.autoclicker

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.MacroPoint
import com.example.autoclicker.data.MacroStroke
import com.example.autoclicker.data.MultitouchGroup
import com.example.autoclicker.data.RecordedMacro
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.MacroRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MacroTest {

    @Test
    fun testMacroSerializationAndDeserialization() {
        val stroke1 = MacroStroke(
            strokeId = 1,
            pointerId = 0,
            startTimeMs = 100L,
            durationMs = 300L,
            points = listOf(
                MacroPoint(100f, 200f, 0L),
                MacroPoint(120f, 250f, 150L),
                MacroPoint(150f, 300f, 300L)
            )
        )

        val stroke2 = MacroStroke(
            strokeId = 2,
            pointerId = 1,
            startTimeMs = 150L,
            durationMs = 200L,
            points = listOf(
                MacroPoint(500f, 600f, 0L),
                MacroPoint(510f, 620f, 200L)
            )
        )

        val group = MultitouchGroup(
            groupId = 1,
            startTimeMs = 100L,
            durationMs = 300L,
            strokes = listOf(stroke1, stroke2)
        )

        val macro = RecordedMacro(
            id = "test_macro_1",
            name = "Тестовый макрос",
            totalDurationMs = 500L,
            strokes = listOf(stroke1, stroke2),
            multitouchGroups = listOf(group),
            rawEventsCount = 5,
            createdAt = 123456789L
        )

        val json = macro.toJson()
        assertNotNull(json)
        assertTrue(json.contains("test_macro_1"))

        val parsed = RecordedMacro.fromJson(json)
        assertNotNull(parsed)
        assertEquals("test_macro_1", parsed?.id)
        assertEquals("Тестовый макрос", parsed?.name)
        assertEquals(500L, parsed?.totalDurationMs)
        assertEquals(2, parsed?.strokes?.size)
        assertEquals(1, parsed?.multitouchGroups?.size)
        assertEquals(2, parsed?.multitouchGroups?.get(0)?.strokes?.size)
        assertEquals(100f, parsed?.strokes?.get(0)?.points?.get(0)?.x ?: 0f, 0.01f)
        assertEquals(500f, parsed?.strokes?.get(1)?.points?.get(0)?.x ?: 0f, 0.01f)
    }

    @Test
    fun testMacroPersistenceInSettingsRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.getInstance(context)

        val macro = RecordedMacro(
            id = "macro_saved",
            name = "Макрос 1",
            totalDurationMs = 1500L,
            strokes = listOf(
                MacroStroke(
                    strokeId = 1,
                    pointerId = 0,
                    startTimeMs = 0L,
                    durationMs = 120L,
                    points = listOf(MacroPoint(300f, 400f, 0L))
                )
            ),
            multitouchGroups = listOf(
                MultitouchGroup(
                    groupId = 1,
                    startTimeMs = 0L,
                    durationMs = 120L,
                    strokes = listOf(
                        MacroStroke(1, 0, 0L, 120L, listOf(MacroPoint(300f, 400f, 0L)))
                    )
                )
            ),
            rawEventsCount = 2
        )

        repo.saveMacro(macro)
        repo.updateMacroConfig(repeatCount = 5, intervalSec = 3)

        val settings = repo.getLatestSettings()
        assertNotNull(settings.recordedMacro)
        assertEquals("macro_saved", settings.recordedMacro?.id)
        assertEquals(5, settings.macroRepeatCount)
        assertEquals(3, settings.macroIntervalSec)
        // Очистка макроса
        repo.clearMacro()
        val afterClear = repo.getLatestSettings()
        assertEquals(null, afterClear.recordedMacro)
    }

    @Test
    fun testMacroRecorderMultitouchGrouping() {
        val recorder = MacroRecorder()
        recorder.start()
        assertTrue(recorder.isRecordingActive())

        val now = SystemClock.uptimeMillis()

        // Создаем MotionEvent нажатия первым пальцем (DOWN)
        val eventDown = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, 150f, 250f, 0
        )
        recorder.processTouchEvent(eventDown)
        eventDown.recycle()

        // Создаем MotionEvent отпускания (UP)
        val eventUp = MotionEvent.obtain(
            now, now + 100L, MotionEvent.ACTION_UP, 150f, 250f, 0
        )
        recorder.processTouchEvent(eventUp)
        eventUp.recycle()

        val macro = recorder.finishRecording()
        assertFalse(recorder.isRecordingActive())
        assertEquals(1, macro.strokes.size)
        assertEquals(1, macro.multitouchGroups.size)
        assertEquals(150f, macro.strokes[0].startX, 0.1f)
        assertEquals(250f, macro.strokes[0].startY, 0.1f)
    }
}
