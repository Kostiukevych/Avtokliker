package com.example.autoclicker.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Плавающая кнопка: WebView со сценой из assets/black_hole_button.html?mode=badge
 * isRunning=true → зелёный контур ядра (JS setRunning).
 */
class BlackHoleBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val webView: WebView
    private var clickListener: (() -> Unit)? = null
    private var longClickListener: (() -> Unit)? = null

    var isRunning: Boolean = false
        set(value) {
            field = value
            post {
                try {
                    webView.evaluateJavascript("window.setRunning && window.setRunning(${if (value) "true" else "false"});", null)
                } catch (_: Exception) {
                }
            }
        }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        webView = createWebView()
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun setOnBadgeClick(listener: () -> Unit) {
        clickListener = listener
    }

    fun setOnBadgeLongClick(listener: () -> Unit) {
        longClickListener = listener
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isClickable = false
            isFocusable = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onBlackHoleClick() {
                    post { clickListener?.invoke() }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(
                        "window.setRunning && window.setRunning(${if (isRunning) "true" else "false"});",
                        null
                    )
                }
            }
            loadUrl("file:///android_asset/black_hole_button.html?mode=badge")
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // перехватываем touch для drag в FloatingOverlayService
        return true
    }

    override fun onDetachedFromWindow() {
        try {
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDetachedFromWindow()
    }
}
