package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Настройки плавающей кнопки (чёрная дыра). Хранятся в SharedPreferences. */
data class BadgeCfg(
    val size: Int = 140,        // сторона окна кнопки, dp
    val opacity: Int = 100,     // 30..100 %
    val float: Boolean = true,  // плавать по экрану
    val speed: Int = 40,        // dp в секунду
    val ringAlways: Boolean = false
)

object BadgePrefs {
    private const val FILE = "badge_prefs"

    fun load(context: Context): BadgeCfg {
        return try {
            val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val d = BadgeCfg()
            BadgeCfg(
                size = p.getInt("size", d.size).coerceIn(80, 240),
                opacity = p.getInt("opacity", d.opacity).coerceIn(30, 100),
                float = p.getBoolean("float", d.float),
                speed = p.getInt("speed", d.speed).coerceIn(10, 200),
                ringAlways = p.getBoolean("ring", d.ringAlways)
            )
        } catch (_: Exception) {
            BadgeCfg()
        }
    }

    fun save(context: Context, cfg: BadgeCfg) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("size", cfg.size)
                .putInt("opacity", cfg.opacity)
                .putBoolean("float", cfg.float)
                .putInt("speed", cfg.speed)
                .putBoolean("ring", cfg.ringAlways)
                .apply()
        } catch (_: Exception) {
        }
    }
}

/**
 * Окно настроек плавающей кнопки. Показывается поверх экрана (TYPE_APPLICATION_OVERLAY),
 * без AlertDialog и без Activity — поэтому не может упасть из-за контекста сервиса.
 */
class BadgeSettingsWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onChanged: (BadgeCfg) -> Unit,
    private val onClosed: () -> Unit
) {
    private var root: View? = null
    val isShowing: Boolean get() = root != null

    private val lime = Color.parseColor("#B6FF3A")

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(initial: BadgeCfg) {
        if (root != null) return
        var cfg = initial

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F00E1016"))
                setStroke(dp(1), Color.parseColor("#80B6FF3A"))
            }
        }

        panel.addView(TextView(context).apply {
            text = "Настройки плавающей кнопки"
            setTextColor(lime)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        fun slider(
            title: String, min: Int, max: Int, value: Int, unit: String,
            apply: (Int) -> Unit
        ) {
            val out = TextView(context).apply {
                setTextColor(lime); textSize = 14f; text = "$value$unit"
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(TextView(context).apply {
                    text = title; setTextColor(Color.parseColor("#E9F3DC")); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(out)
            }
            val sb = SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                try {
                    progressTintList = ColorStateList.valueOf(lime)
                    thumbTintList = ColorStateList.valueOf(lime)
                } catch (_: Throwable) {
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = p + min
                        out.text = "$v$unit"
                        if (fromUser) apply(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            panel.addView(head)
            panel.addView(sb)
        }

        fun toggle(title: String, value: Boolean, apply: (Boolean) -> Unit) {
            val sw = Switch(context).apply {
                isChecked = value
                try {
                    thumbTintList = ColorStateList.valueOf(lime)
                    trackTintList = ColorStateList.valueOf(Color.parseColor("#66B6FF3A"))
                } catch (_: Throwable) {
                }
                setOnCheckedChangeListener { _, c -> apply(c) }
            }
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(2))
                addView(TextView(context).apply {
                    text = title; setTextColor(Color.parseColor("#E9F3DC")); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(sw)
            })
        }

        fun push(new: BadgeCfg) {
            cfg = new
            BadgePrefs.save(context, new)
            onChanged(new)
        }

        slider("Размер кнопки", 80, 240, cfg.size, " dp") { push(cfg.copy(size = it)) }
        slider("Прозрачность", 30, 100, cfg.opacity, "%") { push(cfg.copy(opacity = it)) }
        toggle("Плавать по экрану", cfg.float) { push(cfg.copy(float = it)) }
        slider("Скорость плавания", 10, 200, cfg.speed, "") { push(cfg.copy(speed = it)) }
        toggle("Неоновое кольцо всегда", cfg.ringAlways) { push(cfg.copy(ringAlways = it)) }

        panel.addView(Button(context).apply {
            text = "Закрыть"
            setTextColor(lime)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), lime)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(12) }
            setOnClickListener { dismiss() }
        })

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }

        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.9f).toInt().coerceAtMost(dp(360))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }

        try {
            windowManager.addView(scroll, params)
            root = scroll
        } catch (_: Exception) {
            root = null
        }
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        try {
            windowManager.removeView(r)
        } catch (_: Exception) {
        }
        onClosed()
    }
}
