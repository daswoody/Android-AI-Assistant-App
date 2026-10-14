package de.heimai.app.cards

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Rendert eine HTML-Karte in einer **isolierten WebView** (v1.12). Sicherheitsmodell
 * wie die Web-UI (dort sandboxed iframe ohne same-origin):
 *
 * - JavaScript AN (Karten dürfen interaktiv sein — Inline-JS, Formulare, Buttons).
 * - Der EINZIGE JS-Bridge-Kanal (`HeimHeight`) transportiert ausschließlich die
 *   Inhaltshöhe (eine Zahl) — KEINE App-Daten, kein Token, keine Preferences.
 * - Datei-/Content-Zugriff und DOM-Storage aus.
 * - Jede Navigation/`window.open`/`target=_blank`/`href` öffnet den System-Browser,
 *   NIE in der WebView selbst (so funktionieren z. B. Flugsuch-Buttons mit fertiger URL).
 * - Höhe folgt dem Inhalt (24–800 dp), kein inneres Scrollen, kein horizontales Scrollen.
 *
 * Netz-Requests der Karte selbst müssen nicht funktionieren — Karten sind per
 * Bauauftrag self-contained (Inline-CSS/JS, keine CDNs).
 */
@Composable
fun HtmlCardBody(html: String, desiredHeightPx: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val textHex = String.format("#%06X", 0xFFFFFF and MaterialTheme.colorScheme.onSurface.toArgb())
    val wrapped = remember(html, textHex) { wrapHtml(html, textHex) }
    var heightDp by remember(html) {
        mutableStateOf((desiredHeightPx ?: 120).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP))
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(heightDp.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    setGeolocationEnabled(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                // NUR die Höhe — bewusst kein Objekt mit App-Daten.
                addJavascriptInterface(HeightBridge { px -> post { heightDp = px } }, "HeimHeight")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openExternal(context, request.url)
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    // window.open / target=_blank: Ziel-URL über eine Wegwerf-WebView
                    // abfangen und extern öffnen, statt ein neues Fenster zu laden.
                    override fun onCreateWindow(
                        view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
                    ): Boolean {
                        val sink = WebView(view.context)
                        sink.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                                openExternal(context, req.url)
                                v.destroy()
                                return true
                            }
                        }
                        (resultMsg.obj as WebView.WebViewTransport).webView = sink
                        resultMsg.sendToTarget()
                        return true
                    }
                }
            }
        },
        update = { web ->
            // Nur bei geändertem Inhalt neu laden (nicht bei jeder Höhen-Recomposition).
            if (web.tag != wrapped) {
                web.tag = wrapped
                web.loadDataWithBaseURL(null, wrapped, "text/html", "utf-8", null)
            }
        },
    )
}

/** Bridge, die ausschließlich die gemeldete Inhaltshöhe (dp, geklemmt) durchreicht. */
private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun reportHeight(px: Float) {
        onHeight(px.toInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP))
    }
}

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Wrapper aus der Web-UI-Referenz (Card.svelte): transparenter Hintergrund (Rahmen
 * kommt von der App), Text in Theme-Farbe, Bilder/Tabellen auf Kartenbreite begrenzt,
 * kein horizontales Scrollen. Am Ende ein winziges Skript, das die Inhaltshöhe meldet
 * (on load + ResizeObserver).
 */
private fun wrapHtml(fragment: String, textHex: String): String = """
<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
html,body{margin:0;background:transparent;overflow-x:hidden}
body{font-family:system-ui,sans-serif;color:$textHex;line-height:1.45}
*{box-sizing:border-box} body *{max-width:100% !important}
table{width:100%;border-collapse:collapse} img{max-width:100%}
</style>$fragment
<script>
(function(){
  function r(){ try { HeimHeight.reportHeight(document.documentElement.scrollHeight); } catch(e){} }
  window.addEventListener('load', r);
  if (window.ResizeObserver) { new ResizeObserver(r).observe(document.documentElement); }
  r(); setTimeout(r, 120); setTimeout(r, 400);
})();
</script>
"""

private const val MIN_HEIGHT_DP = 24
private const val MAX_HEIGHT_DP = 800
