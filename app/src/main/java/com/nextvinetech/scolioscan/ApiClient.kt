package com.nextvinetech.scolioscan

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
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
        val severity: String? = null,
        val backType: String? = null,
        val imageFile: java.io.File? = null,
        val imageUrl: String? = null
    )

    /**
     * Data class for angle prediction response
     */
    data class AnglePredictionResponse(
        val mainThoracic: Double,
        val secondaryThoracic: Double,
        val lumbar: Double,
        val severity: String,
        val backType: String,
        val score: Double? = null
    )

    /**
     * Data class for scoliometer analysis request
     * 척추측만계 측정용 파라미터
     */
    data class ScoliometerRequest(
        val mainThoracic: Double,    // 흉추 ATR (1-2번 측정 평균)
        val secondThoracic: Double,  // 흉요추 ATR (3번 측정)
        val lumbar: Double,          // 요추 ATR (4-5번 측정 평균)
        val score: Double,           // 점수 (100 - 총 편차)
        val imageFile: File? = null  // 촬영 이미지 파일 (선택)
    )

    /**
     * Callback interface for API responses
     */
    interface ApiCallback {
        fun onSuccess(response: JSONObject)
        fun onError(error: String)
    }

    /**
     * Callback interface for angle prediction API responses
     */
    interface AnglePredictionCallback {
        fun onSuccess(response: AnglePredictionResponse)
        fun onError(error: String)
    }

    /**
     * Predict angles from an image file.
     *
     * @param imageFile The image file to be analyzed.
     * @param callback Callback for response handling.
     */
    fun predictAngle(
        imageFile: File,
        callback: AnglePredictionCallback
    ) {
        val requestBody: RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                imageFile.name,
                imageFile.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val httpRequest = Request.Builder()
            .url("$BASE_URL/ais/angle")
            .post(requestBody)
            .build()

        Log.d(TAG, "Making request to: ${httpRequest.url}")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API call failed - URL: ${call.request().url}", e)
                callback.onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string()
                    Log.d(TAG, "Response from: ${call.request().url}")
                    Log.d(TAG, "Response code: ${it.code}")
                    Log.d(TAG, "Response body: $responseBody")

                    if (it.isSuccessful && responseBody != null) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val parsedResponse = AnglePredictionResponse(
                                mainThoracic = jsonResponse.getDouble("main_thoracic"),
                                secondaryThoracic = jsonResponse.getDouble("secondary_thoracic"),
                                lumbar = jsonResponse.getDouble("lumbar"),
                                severity = jsonResponse.getString("severity"),
                                backType = jsonResponse.getString("back_type")
                            )
                            Log.d(TAG, "Angle prediction successful: $parsedResponse")
                            callback.onSuccess(parsedResponse)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse angle prediction response", e)
                            callback.onError("Failed to parse response")
                        }
                    } else {
                        val errorMsg = "API error: ${it.code} - $responseBody"
                        Log.e(TAG, "Angle prediction failed with code ${it.code}: $responseBody")
                        callback.onError(errorMsg)
                    }
                }
            }
        })
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
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("analysis_type", request.analysisType.toString())
            .addFormDataPart("thoracic", request.mainThoracic.toString())
            .addFormDataPart("lumbar", request.lumbar.toString())

        request.secondThoracic?.let { builder.addFormDataPart("second_thoracic", it.toString()) }
        request.score?.let { builder.addFormDataPart("score", it.toString()) }
        request.severity?.let { builder.addFormDataPart("severity", it) }
        request.backType?.let { builder.addFormDataPart("back_type", it) }
        request.imageUrl?.let { builder.addFormDataPart("image_url", it) }

        // 이미지 파일 첨부 (필요시)
        if (request.imageFile != null) {
            val mediaType = "image/jpeg".toMediaType()
            val fileBody = request.imageFile.asRequestBody(mediaType)
            builder.addFormDataPart("image", request.imageFile.name, fileBody)
        } else {
            Log.w(TAG, "submitAnalysis called without imageFile")
        }

        val requestBody = builder.build()


        val httpRequest = Request.Builder()
            .url("$BASE_URL/api/analysis/")   // 뒤 슬래시 없어도 됨
            .addHeader("Authorization", "Bearer $jwtToken")
            .post(requestBody)
            .build()

        Log.d(TAG, "Making request to: ${httpRequest.url}")
        Log.d(TAG, "Request body: $requestBody")
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

    /**
     * Submit scoliometer analysis result to the server
     * 척추측만계 측정 결과 제출
     *
     * @param jwtToken JWT token for authentication
     * @param request Scoliometer data to submit
     * @param callback Callback for response handling
     */
    fun submitScoliometerAnalysis(
        jwtToken: String,
        request: ScoliometerRequest,
        callback: ApiCallback
    ) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("analysis_type", AnalysisType.TYPE_SCOLIOMETER.toString())
            .addFormDataPart("thoracic", request.mainThoracic.toString())
            .addFormDataPart("lumbar", request.lumbar.toString())
            .addFormDataPart("score", request.score.toString())

        // 이미지 파일 첨부 (필요시)
        if (request.imageFile != null) {
            val mediaType = "image/jpeg".toMediaType()
            val fileBody = request.imageFile.asRequestBody(mediaType)
            builder.addFormDataPart("image", request.imageFile.name, fileBody)
            Log.d(TAG, "Image file attached: ${request.imageFile.name}")
        } else {
            Log.w(TAG, "submitScoliometerAnalysis called without imageFile")
        }

        val requestBody = builder.build()

        val httpRequest = Request.Builder()
            .url("$BASE_URL/api/analysis/")
            .addHeader("Authorization", "Bearer $jwtToken")
            .post(requestBody)
            .build()

        Log.d(TAG, "Making scoliometer request to: ${httpRequest.url}")
        Log.d(TAG, "Request params: analysis_type=${AnalysisType.TYPE_SCOLIOMETER}, thoracic=${request.mainThoracic}, lumbar=${request.lumbar}, score=${request.score}")
        Log.d(TAG, "Authorization header present: ${jwtToken.take(20)}...")

        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Scoliometer API call failed - URL: ${call.request().url}", e)
                callback.onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val responseBody = it.body?.string() ?: "{}"
                    Log.d(TAG, "Response from: ${call.request().url}")
                    Log.d(TAG, "Response code: ${it.code}")
                    Log.d(TAG, "Response body: $responseBody")

                    if (it.isSuccessful) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            Log.d(TAG, "Scoliometer API call successful: $jsonResponse")
                            callback.onSuccess(jsonResponse)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse response", e)
                            callback.onError("Failed to parse response")
                        }
                    } else {
                        Log.e(TAG, "Scoliometer API call failed with code ${it.code}: $responseBody")
                        callback.onError("API error: ${it.code} - $responseBody")
                    }
                }
            }
        })
    }
}
