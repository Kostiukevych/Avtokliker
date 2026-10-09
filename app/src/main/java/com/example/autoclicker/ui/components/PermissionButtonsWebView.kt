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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Неоновые HUD-кнопки Accessibility / Overlay из assets/permission_buttons.html.
 * Исчезают через JS setPermission после выдачи.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PermissionButtonsWebView(
    isOverlayGranted: Boolean,
    isAccessibilityGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    val overlayRef = remember { arrayOf(onRequestOverlay) }
    val accRef = remember { arrayOf(onRequestAccessibility) }
    overlayRef[0] = onRequestOverlay
    accRef[0] = onRequestAccessibility

    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewHolder[0] = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun requestOverlay() {
                        post { overlayRef[0].invoke() }
                    }

                    @JavascriptInterface
                    fun requestAccessibility() {
                        post { accRef[0].invoke() }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        applyPermissions(view, isOverlayGranted, isAccessibilityGranted)
                    }
                }
                loadUrl("file:///android_asset/permission_buttons.html")
            }
        },
        update = { wv ->
            applyPermissions(wv, isOverlayGranted, isAccessibilityGranted)
        }
    )

    LaunchedEffect(isOverlayGranted, isAccessibilityGranted) {
        applyPermissions(webViewHolder[0], isOverlayGranted, isAccessibilityGranted)
    }
}

private fun applyPermissions(view: WebView?, overlay: Boolean, accessibility: Boolean) {
    if (view == null) return
    val js = "window.setPermissions && window.setPermissions({" +
        "overlay: ${if (overlay) "true" else "false"}, " +
        "accessibility: ${if (accessibility) "true" else "false"}});"
    try {
        view.evaluateJavascript(js, null)
    } catch (_: Exception) {
    }
}
