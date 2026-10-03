package com.example.autoclicker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SwipeAction
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
class SettingsAndSwipeTest {

    @Test
    fun testTenPointsDefaultState() {
        val settings = ClickerSettings()
        assertEquals(10, settings.allPoints.size)
        // Точки 1..3 включены по умолчанию
        assertTrue(settings.point1.enabled)
        assertTrue(settings.point2.enabled)
        assertTrue(settings.point3.enabled)
        // Точки 4..10 выключены по умолчанию
        for (i in 4..10) {
            val p = settings.getPointById(i)
            assertEquals(i, p.id)
            assertFalse("Точка $i должна быть выключена по умолчанию", p.enabled)
        }
    }

    @Test
    fun testSwipeActionDefaults() {
        val swipe = SwipeAction(id = 1)
        assertEquals(1, swipe.id)
        assertFalse(swipe.enabled)
        assertEquals(300L, swipe.durationMs)
        assertEquals(10, swipe.intervalSec)
        assertFalse(swipe.isConfigured)

        val configuredSwipe = swipe.copy(startX = 100f, startY = 200f, endX = 100f, endY = 800f)
        assertTrue(configuredSwipe.isConfigured)
    }

    @Test
    fun testSettingsRepositorySwipeAndPointsPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository.getInstance(context)

        // Тест сохранения точки 7
        repo.updatePointCoordinates(
            pointId = 7,
            x = 350f,
            y = 750f,
            orientation = 1,
            screenWidth = 1080,
            screenHeight = 1920
        )
        repo.updatePointConfig(pointId = 7, enabled = true, count = 3, interval = 4)

        var latest = repo.getLatestSettings()
        val p7 = latest.getPointById(7)
        assertEquals(350f, p7.x, 0.01f)
        assertEquals(750f, p7.y, 0.01f)
        assertTrue(p7.enabled)
        assertEquals(3, p7.clickCount)
        assertEquals(4, p7.intervalSec)

        // Тест свайпа 2
        repo.updateSwipesMasterEnabled(true)
        repo.updateSwipeCoordinates(
            swipeId = 2,
            startX = 200f,
            startY = 500f,
            endX = 200f,
            endY = 1200f
        )
        repo.updateSwipeConfig(swipeId = 2, enabled = true, durationMs = 450L, intervalSec = 15)

        latest = repo.getLatestSettings()
        assertTrue(latest.isSwipesEnabled)
        val s2 = latest.getSwipeById(2)
        assertEquals(2, s2.id)
        assertTrue(s2.enabled)
        assertEquals(200f, s2.startX, 0.01f)
        assertEquals(500f, s2.startY, 0.01f)
        assertEquals(200f, s2.endX, 0.01f)
        assertEquals(1200f, s2.endY, 0.01f)
        assertEquals(450L, s2.durationMs)
        assertEquals(15, s2.intervalSec)
        assertTrue(s2.isConfigured)
    }
}
