package com.nextvinetech.scolioscan

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ServiceActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ServiceActivity"
        private const val SERVICE_URL = "https://nextvine.primers.co.kr/login"
        const val EXTRA_JWT_TOKEN = "jwt_token"

        // Error tracking thresholds
        private const val ERROR_THRESHOLD = 5 // Show network error after this many failures
        private const val ERROR_RESET_DELAY_MS = 10000L // Reset error count after 10 seconds of no errors
    }

    private lateinit var webView: WebView
    private lateinit var noInternetContent: View
    private lateinit var retryButton: Button
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastLoadedUrl: String = SERVICE_URL

    // Error tracking for resource loading
    private var resourceErrorCount = 0
    private var isShowingNetworkError = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val errorResetRunnable = Runnable {
        resourceErrorCount = 0
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
        noInternetContent = findViewById(R.id.noInternetContent)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener {
            retryLoading()
        }

        setupWebView()
        webView.loadUrl(SERVICE_URL)
    }

    private fun retryLoading() {
        if (isNetworkAvailable()) {
            hideNetworkErrorScreen()
            resourceErrorCount = 0
            webView.reload()
        }
    }

    private fun showNetworkErrorScreen() {
        if (!isShowingNetworkError) {
            isShowingNetworkError = true
            runOnUiThread {
                noInternetContent.visibility = View.VISIBLE
            }
        }
    }

    private fun hideNetworkErrorScreen() {
        isShowingNetworkError = false
        runOnUiThread {
            noInternetContent.visibility = View.GONE
        }
    }

    private fun onResourceLoadError() {
        resourceErrorCount++
        Log.d(TAG, "Resource load error count: $resourceErrorCount")

        // Reset the error count after some time of no errors
        mainHandler.removeCallbacks(errorResetRunnable)
        mainHandler.postDelayed(errorResetRunnable, ERROR_RESET_DELAY_MS)

        // Check if we should show network error
        if (resourceErrorCount >= ERROR_THRESHOLD && !isNetworkAvailable()) {
            showNetworkErrorScreen()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            // Enable caching for better offline experience
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { lastLoadedUrl = it }
                // Reset error count when page starts loading
                resourceErrorCount = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // If page finished loading successfully and we're showing error, hide it
                if (isShowingNetworkError && isNetworkAvailable()) {
                    hideNetworkErrorScreen()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error: ${error?.description} for ${request?.url}")

                if (request?.isForMainFrame == true) {
                    // Main frame error - show network error immediately
                    showNetworkErrorScreen()
                } else {
                    // Resource error (image, API, etc.)
                    onResourceLoadError()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val statusCode = errorResponse?.statusCode ?: 0
                Log.e(TAG, "HTTP error $statusCode for ${request?.url}")

                // Track HTTP errors for resources (except successful status codes)
                if (statusCode >= 400) {
                    onResourceLoadError()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView error (legacy): $description for $failingUrl")
                onResourceLoadError()
            }
        }

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
     * - Android.openScoliometer(jwtToken) : Opens scoliometer for ATR measurement
     * - Android.isAndroidApp() : Returns true if running in Android app
     * - Android.checkNetwork() : Returns true if network is available
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
         * Opens Scoliometer activity for ATR (Angle of Trunk Rotation) measurement
         * @param jwtToken JWT token for authenticated API calls
         */
        @JavascriptInterface
        fun openScoliometer(jwtToken: String) {
            val intent = Intent(this@ServiceActivity, ScoliometerActivity::class.java).apply {
                putExtra(ScoliometerActivity.EXTRA_JWT_TOKEN, jwtToken)
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

        /**
         * Check if network is available
         * @return true if network is connected
         */
        @JavascriptInterface
        fun checkNetwork(): Boolean {
            return isNetworkAvailable()
        }

        /**
         * Show network error screen from JavaScript
         * Can be called when React detects API failures
         */
        @JavascriptInterface
        fun showNetworkError() {
            runOnUiThread {
                showNetworkErrorScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If we were showing network error and network is back, hide it
        if (isShowingNetworkError && isNetworkAvailable()) {
            hideNetworkErrorScreen()
            webView.reload()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(errorResetRunnable)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If showing no internet screen, try to go back in webview or exit
        if (noInternetContent.visibility == View.VISIBLE) {
            if (isNetworkAvailable()) {
                hideNetworkErrorScreen()
                webView.reload()
            } else if (webView.canGoBack()) {
                hideNetworkErrorScreen()
                webView.goBack()
            } else {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            return
        }

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
