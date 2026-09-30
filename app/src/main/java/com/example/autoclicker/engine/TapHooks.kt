package com.example.autoclicker.engine

/**
 * Хуки жизненного цикла нажатия для взаимодействия с плавающим оверлеем или анимациями.
 */
interface TapHooks {
    suspend fun beforeTap(pointId: Int, x: Float, y: Float, tapIndex: Int = 1, totalTaps: Int = 1) {}
    suspend fun afterTap(pointId: Int, x: Float, y: Float, success: Boolean, tapIndex: Int = 1, totalTaps: Int = 1) {}
}
