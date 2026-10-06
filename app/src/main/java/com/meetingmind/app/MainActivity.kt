package com.meetingmind.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val TAG = "MainActivity"

    // ─── Permisos requeridos en runtime ──────────────────────────
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 🔧 FIX: paréntesis explícitos + Elvis para evitar conflicto de tipos
        val micOk = (result[Manifest.permission.RECORD_AUDIO] ?: false) ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED)

        if (micOk) {
            launchRecordingService()
        } else {
            Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_LONG).show()
            injectJs("window.receiveNativeError && window.receiveNativeError('Permiso de micrófono denegado');")
        }
    }

    // ─── Ciclo de vida ───────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0B132B.toInt())
        }
        setContentView(webView)

        // Callback que RecordingService usará para devolvernos el audio
        RecordingBridge.onAudioReady = { base64 -> sendAudioToWeb(base64) }
        RecordingBridge.onRecordingStopped = {
            runOnUiThread { Log.d(TAG, "Servicio de grabación detenido.") }
        }

        // ─── WebViewAssetLoader (CORS-friendly) ────────────────
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host != "appassets.androidplatform.net") {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // ─── Puente nativo "AndroidNative" ─────────────────────
        webView.addJavascriptInterface(WebAppInterface(), "AndroidNative")

        // ─── Cargar HTML desde el asset loader ─────────────────
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    // ═══════════════════════════════════════════════════════════
    // PUENTE JS ↔ KOTLIN
    // ═══════════════════════════════════════════════════════════
    inner class WebAppInterface {

        @JavascriptInterface
        fun startRecording() {
            runOnUiThread { checkAndStartRecording() }
        }

        @JavascriptInterface
        fun stopRecording() {
            runOnUiThread { stopRecordingService() }
        }

        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // ─── Permisos + arranque del servicio ────────────────────────
    private fun checkAndStartRecording() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            launchRecordingService()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
    }

    // ─── Envío del audio en Base64 al WebView (en chunks) ───────
    private fun sendAudioToWeb(base64Audio: String) {
        runOnUiThread {
            val total = base64Audio.length
            val chunkSize = 400_000
            Log.d(TAG, "Enviando ${total} chars Base64 al WebView")

            // 1. Inicializar buffer
            webView.evaluateJavascript(
                "window.__nativeAudioBuffer = ''; window.__nativeAudioExpected = $total;",
                null
            )

            // 2. Enviar por trozos
            var offset = 0
            while (offset < total) {
                val end = minOf(offset + chunkSize, total)
                val chunk = base64Audio.substring(offset, end)
                val quoted = JSONObject.quote(chunk)
                webView.evaluateJavascript("window.__nativeAudioBuffer += $quoted;", null)
                offset = end
            }

            // 3. Disparar callback en el HTML
            webView.evaluateJavascript(
                """
                (function(){
                    var b = window.__nativeAudioBuffer;
                    window.__nativeAudioBuffer = null;
                    if (typeof window.receiveNativeAudio === 'function') {
                        window.receiveNativeAudio(b);
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    private fun injectJs(js: String) {
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    override fun onDestroy() {
        RecordingBridge.onAudioReady = null
        RecordingBridge.onRecordingStopped = null
        webView.destroy()
        super.onDestroy()
    }
}
