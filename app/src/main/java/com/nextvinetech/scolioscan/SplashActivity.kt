package com.nextvinetech.scolioscan

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DELAY_MS = 2500L
    }

    private lateinit var splashContent: View
    private lateinit var noInternetContent: View
    private lateinit var retryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        splashContent = findViewById(R.id.splashContent)
        noInternetContent = findViewById(R.id.noInternetContent)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener {
            checkNetworkAndProceed()
        }

        checkNetworkAndProceed()
    }

    private fun checkNetworkAndProceed() {
        if (isNetworkAvailable()) {
            showSplashScreen()
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, ServiceActivity::class.java))
                finish()
            }, SPLASH_DELAY_MS)
        } else {
            showNoInternetScreen()
        }
    }

    private fun showSplashScreen() {
        splashContent.visibility = View.VISIBLE
        noInternetContent.visibility = View.GONE
    }

    private fun showNoInternetScreen() {
        splashContent.visibility = View.GONE
        noInternetContent.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
