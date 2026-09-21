package com.tomasthrawat.hyoukacatbox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object AccountApi {
    fun fetchFiles(endpoint: String, apiKey: String, authMode: Int): List<AccountFile> {
        val finalEndpoint = if (authMode == 2 && apiKey.isNotBlank()) {
            val separator = if (endpoint.contains("?")) "&" else "?"
            endpoint + separator + "api_key=" + URLEncoder.encode(apiKey, "UTF-8")
        } else {
            endpoint
        }

        val connection = (URL(finalEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")

            when (authMode.coerceIn(0, 2)) {
                0 -> if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer " + apiKey)
                }
                1 -> if (apiKey.isNotBlank()) {
                    setRequestProperty("X-API-Key", apiKey)
                }
            }
        }

        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException(
                    "HTTP " + code + if (body.isBlank()) "" else ": " + body
                )
            }

            return parseFiles(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFiles(body: String): List<AccountFile> {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            throw IllegalStateException("API returned an empty response")
        }

        val root: Any = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> throw IllegalStateException("API must return JSON")
        }

        val array = extractArray(root)
            ?: throw IllegalStateException(
                "No file array found. Expected JSON like [ ... ] or {files:[ ... ]}"
            )

        val output = ArrayList<AccountFile>()
        val seen = HashSet<String>()

        for (i in 0 until array.length()) {
            val file = parseItem(array.opt(i)) ?: continue
            if (seen.add(file.url)) output.add(file)
        }

        return output
    }

    private fun extractArray(value: Any): JSONArray? {
        when (value) {
            is JSONArray -> return value
            is JSONObject -> {
                for (key in listOf("files", "data", "items", "results")) {
                    if (!value.has(key)) continue
                    val child = value.opt(key) ?: continue
                    val array = extractArray(child)
                    if (array != null) return array
                }
            }
        }
        return null
    }

    private fun parseItem(value: Any?): AccountFile? {
        if (value is String) {
            val url = value.trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return AccountFile(null, guessName(url), url, null)
            }
            return null
        }

        if (value !is JSONObject) return null

        val url = firstString(
            value,
            "url", "link", "download_url", "downloadUrl", "direct_url", "directUrl"
        ) ?: return null

        val name = firstString(
            value,
            "name", "filename", "file_name", "original_name", "originalName"
        )?.ifBlank { null } ?: guessName(url)

        val id = firstString(value, "id", "file_id", "fileId")

        val size = when (val raw = value.opt("size")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> value.optLong("size_bytes", -1L).takeIf { it >= 0L }
        }

        return AccountFile(id, name, url, size)
    }

    private fun firstString(value: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val text = value.optString(key, "").trim()
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun guessName(url: String): String {
        val path = runCatching { URL(url).path }.getOrNull().orEmpty()
        val raw = path.substringAfterLast('/').ifBlank { "download.bin" }

        return runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrDefault(raw)
            .ifBlank { "download.bin" }
    }
}
