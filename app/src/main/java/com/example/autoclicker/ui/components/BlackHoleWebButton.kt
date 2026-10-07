package com.example.autoclicker.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Полная сцена чёрной дыры из assets/black_hole_button.html (та же анимация и фон).
 * Клик вызывает [onClick] — открытие плавающего окна.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BlackHoleWebButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp
) {
    val context = LocalContext.current
    val clickRef = remember { arrayOf(onClick) }
    clickRef[0] = onClick

    DisposableEffect(Unit) {
        onDispose { }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#02030a"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onBlackHoleClick() {
                        post { clickRef[0].invoke() }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // На всякий случай дублируем бинд клика
                        view?.evaluateJavascript(
                            """
                            (function(){
                              var b = document.getElementById('bh');
                              if (!b || b.__androidBound) return;
                              b.__androidBound = true;
                              b.addEventListener('click', function(){
                                try { AndroidBridge.onBlackHoleClick(); } catch(e) {}
                              });
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
                loadUrl("file:///android_asset/black_hole_button.html")
            }
        },
        update = { /* no-op */ }
    )
}
