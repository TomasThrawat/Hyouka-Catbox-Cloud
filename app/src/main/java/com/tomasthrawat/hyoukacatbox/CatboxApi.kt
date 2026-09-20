package com.tomasthrawat.hyoukacatbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object CatboxApi {
    private const val API_URL = "https://catbox.moe/user/api.php"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    fun upload(file: File, userhash: String? = null): Result<String> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .apply {
                if (!userhash.isNullOrBlank()) {
                    addFormDataPart("userhash", userhash)
                }
            }
            .addFormDataPart(
                "fileToUpload",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: $text")
            }

            if (text.startsWith("https://") || text.startsWith("http://")) {
                text
            } else {
                error(
                    if (text.isBlank()) {
                        "Catbox returned an empty response"
                    } else {
                        text
                    }
                )
            }
        }
    }
}
