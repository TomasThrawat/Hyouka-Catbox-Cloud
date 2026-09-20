package com.tomasthrawat.hyoukacatbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
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
                if (!userhash.isNullOrBlank()) addFormDataPart("userhash", userhash)
            }
            .addFormDataPart(
                "fileToUpload",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        val request = Request.Builder().url(API_URL).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            if (text.startsWith("https://") || text.startsWith("http://")) text
            else error(if (text.isBlank()) "Catbox returned an empty response" else text)
        }
    }

    fun files(userhash: String): Result<List<String>> = runCatching {
        require(userhash.isNotBlank()) { "userhash is required" }
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "getaccount")
            .addFormDataPart("userhash", userhash)
            .build()
        val request = Request.Builder().url(API_URL).post(form).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            parseFiles(text)
        }
    }

    private fun parseFiles(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    val url = item?.optString("filepath")?.takeIf { it.isNotBlank() }
                    if (url != null) add(url)
                }
            }
        } catch (_: Exception) {
            text.lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
        }
    }
}
