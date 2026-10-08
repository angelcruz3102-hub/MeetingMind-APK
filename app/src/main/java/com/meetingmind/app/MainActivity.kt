package com.meetingmind.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
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

    // 🔥 NUEVO — Referencia para el callback del selector de archivos
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ─── Permisos requeridos en runtime ──────────────────────────
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = (result[Manifest.permission.RECORD_AUDIO] ?: false) ||
                (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED)

        val storageOk = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            (result[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED)
        } else true

        if (micOk && storageOk) {
            launchRecordingService()
        } else {
            val msg = when {
                !micOk -> "Permiso de micrófono denegado"
                !storageOk -> "Permiso de almacenamiento denegado"
                else -> "Permisos insuficientes"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            injectJs("window.receiveNativeError && window.receiveNativeError('$msg');")
        }
    }

    // 🔥 NUEVO — Launcher del selector de archivos
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null

        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris: Array<Uri>? = when {
            // Selección múltiple
            data?.clipData != null -> {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            }
            // Selección simple
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }

        callback.onReceiveValue(uris)
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

        RecordingBridge.onAudioReady = { base64 -> sendAudioToWeb(base64) }
        RecordingBridge.onRecordingStopped = {
            runOnUiThread { Log.d(TAG, "Servicio de grabación detenido.") }
        }

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

        // ═══════════════════════════════════════════════════════
        // 🔥 WebChromeClient CON SOPORTE PARA <input type="file">
        // ═══════════════════════════════════════════════════════
        webView.webChromeClient = object : WebChromeClient() {

            // Permite getUserMedia si se usa el modo web
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            // 🔥 CLAVE: abrir el selector de archivos nativo
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancelar cualquier picker pendiente
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                return try {
                    val intent = fileChooserParams?.createIntent()
                    if (intent == null) {
                        this@MainActivity.filePathCallback = null
                        return false
                    }
                    // El HTML ya define los mime types en el atributo `accept`
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error abriendo file chooser", e)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        // ─── Puente nativo "AndroidNative" ─────────────────────
        webView.addJavascriptInterface(WebAppInterface(), "AndroidNative")

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

    private fun sendAudioToWeb(base64Audio: String) {
        runOnUiThread {
            val total = base64Audio.length
            val chunkSize = 400_000
            Log.d(TAG, "Enviando ${total} chars Base64 al WebView")

            webView.evaluateJavascript(
                "window.__nativeAudioBuffer = ''; window.__nativeAudioExpected = $total;",
                null
            )

            var offset = 0
            while (offset < total) {
                val end = minOf(offset + chunkSize, total)
                val chunk = base64Audio.substring(offset, end)
                val quoted = JSONObject.quote(chunk)
                webView.evaluateJavascript("window.__nativeAudioBuffer += $quoted;", null)
                offset = end
            }

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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
