package com.example.autoclicker.engine

/**
 * Фаза катки для автомимикрии джойстиков.
 * SmartEngine сообщает о «Продолжить» / «Начать».
 */
object JoystickMatchPhase {
    enum class Phase {
        /** В катке — сценарий движения активен */
        IN_MATCH,
        /** Между катками (после «Продолжить») — джойстики на паузе */
        BETWEEN_MATCHES
    }

    @Volatile
    var phase: Phase = Phase.IN_MATCH
        private set

    fun notifyContinueDetected() {
        phase = Phase.BETWEEN_MATCHES
    }

    fun notifyStartDetected() {
        phase = Phase.IN_MATCH
    }

    fun reset() {
        phase = Phase.IN_MATCH
    }
}
