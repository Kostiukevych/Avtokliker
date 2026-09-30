class CalibrationOverlayView(
    context: Context,
    private val pointId: Int,
    private val onDismissed: (() -> Unit)? = null
) {
    private val markerWindow: CalibrationMarkerWindow
    private val controlPanel: CalibrationControlPanel
    private val settingsRepo = SettingsRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dismissed = false

    init {
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "SET POINT $pointId START")

        markerWindow = CalibrationMarkerWindow(
            context = context,
            pointId = pointId,
            onCoordinatesUpdated = { _, _ -> },
            onDismissed = { dismiss() }
        )

        controlPanel = CalibrationControlPanel(
            context = context,
            pointId = pointId,
            settingsRepo = settingsRepo,
            getCoordinates = { markerWindow.getCoordinates() },
            onDismissed = { dismiss() }
        )
    }

    fun show() {
        markerWindow.show()
        controlPanel.show()
        
        scope.launch {
            delay(90_000L)
            dismiss()
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        scope.cancel()
        markerWindow.dismiss()
        controlPanel.dismiss()
        onDismissed?.invoke()
    }
}
