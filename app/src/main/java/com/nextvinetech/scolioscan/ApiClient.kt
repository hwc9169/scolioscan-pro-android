package com.nextvinetech.scolioscan

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API Client for NextVine backend communication
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "https://nextvine-service.primers.co.kr"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Analysis type constants
     */
    object AnalysisType {
        const val TYPE_2D = 1
        const val TYPE_3D = 2
        const val TYPE_SCOLIOMETER = 3
    }

    /**
     * Data class for analysis request
     */
    data class AnalysisRequest(
        val analysisType: Int,
        val mainThoracic: Double,
        val secondThoracic: Double? = null,
        val lumbar: Double,
        val score: Double? = null,
        val imageUrl: String? = null
    )

    /**
     * Callback interface for API responses
     */
    interface ApiCallback {
        fun onSuccess(response: JSONObject)
        fun onError(error: String)
    }

    /**
     * Submit spine analysis result to the server
     *
     * @param jwtToken JWT token for authentication
     * @param request Analysis data to submit
     * @param callback Callback for response handling
     */
    fun submitAnalysis(
        jwtToken: String,
        request: AnalysisRequest,
        callback: ApiCallback
    ) {
        val jsonBody = JSONObject().apply {
            put("analysis_type", request.analysisType)
            put("main_thoracic", request.mainThoracic)
            request.secondThoracic?.let { put("second_thoracic", it) }
            put("lumbar", request.lumbar)
            request.score?.let { put("score", it) }
            request.imageUrl?.let { put("image_url", it) }
        }

        val requestBody = jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/api/analysis/")
            .addHeader("Authorization", "Bearer $jwtToken")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        Log.d(TAG, "Making request to: ${httpRequest.url}")
        Log.d(TAG, "Request body: $jsonBody")
        Log.d(TAG, "Authorization header present: ${jwtToken.take(20)}...")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API call failed - URL: ${call.request().url}", e)
                callback.onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string() ?: "{}"
                    Log.d(TAG, "Response from: ${call.request().url}")
                    Log.d(TAG, "Response code: ${it.code}")
                    Log.d(TAG, "Response headers: ${it.headers}")
                    Log.d(TAG, "Response body: $responseBody")

                    if (it.isSuccessful) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            Log.d(TAG, "API call successful: $jsonResponse")
                            callback.onSuccess(jsonResponse)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse response", e)
                            callback.onError("Failed to parse response")
                        }
                    } else {
                        Log.e(TAG, "API call failed with code ${it.code}: $responseBody")
                        callback.onError("API error: ${it.code} - $responseBody")
                    }
                }
            }
        })
    }
}
