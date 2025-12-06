package com.nextvinetech.scolioscan

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ServiceActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val SERVICE_URL = "https://nextvine.primers.co.kr/login"
        const val EXTRA_JWT_TOKEN = "jwt_token"
    }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        filePathCallback?.onReceiveValue(
            if (uri != null) arrayOf(uri) else null
        )
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service)

        // Handle window insets for edge-to-edge display
        val rootLayout = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView = findViewById(R.id.webView)
        setupWebView()
        webView.loadUrl(SERVICE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = WebViewClient()

        // Custom WebChromeClient to handle file chooser
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing callback
                this@ServiceActivity.filePathCallback?.onReceiveValue(null)
                this@ServiceActivity.filePathCallback = filePathCallback

                // Get accept types from the file chooser params
                val acceptTypes = fileChooserParams?.acceptTypes
                val mimeType = if (acceptTypes != null && acceptTypes.isNotEmpty() && acceptTypes[0].isNotEmpty()) {
                    acceptTypes[0]
                } else {
                    "*/*"
                }

                // Launch file picker
                filePickerLauncher.launch(mimeType)
                return true
            }
        }

        webView.addJavascriptInterface(WebAppInterface(), "Android")
    }

    /**
     * JavaScript Interface for web-to-native communication
     *
     * Usage from JavaScript:
     * - Android.open2DCamera(jwtToken) : Opens 2D camera for spine measurement
     * - Android.isAndroidApp() : Returns true if running in Android app
     */
    inner class WebAppInterface {
        /**
         * Opens 2D camera activity for spine measurement
         * @param jwtToken JWT token for authenticated API calls
         */
        @JavascriptInterface
        fun open2DCamera(jwtToken: String) {
            val intent = Intent(this@ServiceActivity, MainActivity::class.java).apply {
                putExtra(EXTRA_JWT_TOKEN, jwtToken)
            }
            startActivity(intent)
        }

        /**
         * Check if the app is running on Android
         * @return true if running in Android WebView
         */
        @JavascriptInterface
        fun isAndroidApp(): Boolean {
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.handleBackButton ? window.handleBackButton() : false") { result ->
            if (result == "false" || result == "null" || result == "undefined") {
                // Web did not handle - perform default back behavior
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
            }
            // result == "true" means web handled the back button
        }
    }
}
