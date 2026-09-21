package com.tomasthrawat.hyoukacatbox

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var filesContainer: LinearLayout
    private lateinit var userHash: EditText
    private lateinit var saveHash: CheckBox
    private lateinit var apiUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var saveApi: CheckBox
    private lateinit var authMode: Spinner

    private val api = CatboxApi
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            status.text = "No files selected"
        } else {
            uploadAll(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSettings()
        showApiHint()
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }

        val title = TextView(this).apply {
            text = "Hyouka Cloud"
            setTextColor(Color.WHITE)
            textSize = 28f
        }

        status = TextView(this).apply {
            text = "Ready"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        }

        val upload = Button(this).apply {
            text = "Upload files"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }

        userHash = editText("Catbox userhash (optional)", password = true)

        saveHash = CheckBox(this).apply {
            text = "Save userhash on this device"
            setTextColor(Color.WHITE)
        }

        val divider1 = label("Account Files API")
        apiUrl = editText("API URL, for example https://example.com/files")
        apiKey = editText("API key", password = true)

        authMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "Authorization: Bearer <key>",
                    "X-API-Key: <key>",
                    "Query: ?api_key=<key>"
                )
            )
        }

        saveApi = CheckBox(this).apply {
            text = "Save API settings on this device"
            setTextColor(Color.WHITE)
        }

        val sync = Button(this).apply {
            text = "Sync My Files"
            setOnClickListener { syncMyFiles() }
        }

        val note = TextView(this).apply {
            text = "My Files now comes from the configured API. The endpoint must return JSON containing a file list."
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }

        filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(filesContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        root.addView(title)
        root.addView(status)
        root.addView(upload, matchParams(dp(8)))
        root.addView(userHash, matchParams(dp(8)))
        root.addView(saveHash)
        root.addView(divider1, matchParams(dp(8)))
        root.addView(apiUrl, matchParams(dp(8)))
        root.addView(apiKey, matchParams(dp(8)))
        root.addView(authMode, matchParams(dp(8)))
        root.addView(saveApi)
        root.addView(sync, matchParams(dp(8)))
        root.addView(note)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    private fun showApiHint() {
        if (prefs.getString(KEY_API_URL, "").orEmpty().isBlank()) {
            addInfoRow("API is not configured yet. Add the API URL and key you receive, then press Sync My Files.")
        }
    }

    private fun loadSettings() {
        prefs.getString(KEY_USERHASH, "").orEmpty().takeIf { it.isNotBlank() }?.let {
            userHash.setText(it)
            saveHash.isChecked = true
        }

        prefs.getString(KEY_API_URL, "").orEmpty().takeIf { it.isNotBlank() }?.let {
            apiUrl.setText(it)
        }

        prefs.getString(KEY_API_KEY, "").orEmpty().takeIf { it.isNotBlank() }?.let {
            apiKey.setText(it)
            saveApi.isChecked = true
        }

        authMode.setSelection(prefs.getInt(KEY_AUTH_MODE, 0).coerceIn(0, 2))

        saveHash.setOnCheckedChangeListener { _, checked ->
            if (checked) saveCurrentUserHash()
            else prefs.edit().remove(KEY_USERHASH).apply()
        }

        saveApi.setOnCheckedChangeListener { _, checked ->
            if (checked) saveCurrentApiSettings()
            else prefs.edit()
                .remove(KEY_API_URL)
                .remove(KEY_API_KEY)
                .remove(KEY_AUTH_MODE)
                .apply()
        }
    }

    private fun saveCurrentUserHash() {
        val value = userHash.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            prefs.edit().remove(KEY_USERHASH).apply()
            status.text = "Enter a userhash to save it"
        } else {
            prefs.edit().putString(KEY_USERHASH, value).apply()
            status.text = "Userhash saved"
        }
    }

    private fun saveCurrentApiSettings() {
        val url = apiUrl.text?.toString()?.trim().orEmpty()
        val key = apiKey.text?.toString()?.trim().orEmpty()

        if (url.isBlank()) {
            status.text = "Enter an API URL to save API settings"
            return
        }

        prefs.edit()
            .putString(KEY_API_URL, url)
            .putString(KEY_API_KEY, key)
            .putInt(KEY_AUTH_MODE, authMode.selectedItemPosition)
            .apply()
        status.text = "API settings saved"
    }

    private fun uploadAll(uris: List<Uri>) {
        if (saveHash.isChecked) saveCurrentUserHash()

        lifecycleScope.launch {
            var completed = 0
            var failed = 0
            val hash = userHash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

            try {
                for ((index, uri) in uris.withIndex()) {
                    status.text = "Uploading " + (index + 1) + "/" + uris.size + "..."

                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val originalName = contentName(uri)
                            val file = copyToCache(uri, originalName)
                            try {
                                api.upload(file, hash).getOrThrow()
                            } finally {
                                file.delete()
                            }
                        }
                    }

                    result.onSuccess { url ->
                        completed++
                        addFileRow(contentName(uri), url)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failed++
                        addErrorRow("Upload error: " + (error.message ?: error.javaClass.simpleName))
                    }
                }

                status.text = "Done: " + completed + " uploaded, " + failed + " failed"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                status.text = "Upload error: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun syncMyFiles() {
        if (saveApi.isChecked) saveCurrentApiSettings()

        val endpoint = apiUrl.text?.toString()?.trim().orEmpty()
        val key = apiKey.text?.toString()?.trim().orEmpty()
        val mode = authMode.selectedItemPosition

        if (endpoint.isBlank()) {
            status.text = "Enter the API URL first"
            return
        }

        lifecycleScope.launch {
            status.text = "Loading My Files..."

            val result = withContext(Dispatchers.IO) {
                runCatching { AccountApi.fetchFiles(endpoint, key, mode) }
            }

            result.onSuccess { files ->
                filesContainer.removeAllViews()

                if (files.isEmpty()) {
                    addInfoRow("The API returned no files.")
                    status.text = "My Files: 0"
                    return@onSuccess
                }

                files.forEach { addRemoteFileRow(it) }
                status.text = "My Files: " + files.size
            }.onFailure { error ->
                addErrorRow("API error: " + (error.message ?: error.javaClass.simpleName))
                status.text = "API request failed"
            }
        }
    }

    private fun addRemoteFileRow(file: AccountFile) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val name = TextView(this).apply {
            text = file.name
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }

        val url = TextView(this).apply {
            text = file.url
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setTextIsSelectable(true)
            maxLines = 3
        }

        val download = Button(this).apply {
            text = "Download"
            setOnClickListener { enqueueDownload(file.name, file.url) }
        }

        row.addView(name)
        row.addView(url, matchParams(dp(4)))
        row.addView(download, matchParams(dp(4)))

        val divider = View(this).apply { setBackgroundColor(Color.DKGRAY) }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ))
        }

        filesContainer.addView(wrapper)
    }

    private fun addFileRow(name: String, url: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val title = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }

        val link = TextView(this).apply {
            text = url
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setTextIsSelectable(true)
            maxLines = 3
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }

        val download = Button(this).apply {
            text = "Download"
            setOnClickListener { enqueueDownload(name, url) }
        }

        row.addView(title)
        row.addView(link, matchParams(dp(4)))
        row.addView(download, matchParams(dp(4)))
        filesContainer.addView(row, 0)
    }

    private fun addInfoRow(message: String) {
        val info = TextView(this).apply {
            text = message
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        filesContainer.addView(info, 0)
    }

    private fun addErrorRow(message: String) {
        val error = TextView(this).apply {
            text = message
            setTextColor(Color.RED)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        filesContainer.addView(error, 0)
    }

    private fun enqueueDownload(fileName: String, url: String) {
        runCatching {
            val safeName = fileName
                .filter {
                    it.code >= 32 &&
                        it !in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
                }
                .take(180)
                .ifBlank { "download.bin" }

            val extension = safeName.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setDescription("Downloading from cloud API")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType(mime)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    safeName
                )

            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            status.text = "Download started: " + safeName
        }.onFailure {
            status.text = "Download error: " + (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val safe = name
            .filter { it.code >= 32 && it !in charArrayOf('/', '\\') }
            .take(180)
            .ifBlank { "upload.bin" }

        val file = File.createTempFile("catbox_", "_" + safe, cacheDir)
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected file")

        input.use { source ->
            file.outputStream().use { target -> source.copyTo(target) }
        }

        if (!file.exists() || file.length() == 0L) {
            file.delete()
            throw IOException("Selected file is empty or could not be copied")
        }

        return file
    }

    private fun contentName(uri: Uri): String {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull() ?: originalDisplayName(uri)
    }

    private fun originalDisplayName(uri: Uri): String {
        val decoded = runCatching {
            URLDecoder.decode(uri.lastPathSegment.orEmpty(), "UTF-8")
        }.getOrNull().orEmpty()

        return decoded.substringAfterLast('/').ifBlank { "upload.bin" }
    }

    private fun editText(hintText: String, password: Boolean): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
        }
    }

    private fun matchParams(top: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS_NAME = "hyouka_catbox"
        const val KEY_USERHASH = "saved_userhash"
        const val KEY_API_URL = "account_api_url"
        const val KEY_API_KEY = "account_api_key"
        const val KEY_AUTH_MODE = "account_api_auth_mode"
    }
}
