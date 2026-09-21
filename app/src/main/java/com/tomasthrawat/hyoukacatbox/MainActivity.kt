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
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
    private lateinit var urlInput: EditText

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
        loadUploadHistory()
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
            setOnClickListener {
                picker.launch(arrayOf("*/*"))
            }
        }

        userHash = editText("Catbox userhash (optional for upload)", password = true)
        saveHash = CheckBox(this).apply {
            text = "Save userhash on this device"
            setTextColor(Color.WHITE)
        }

        val apiLabel = label("Catbox API")
        apiUrl = editText("https://catbox.moe/user/api.php", password = false).apply {
            setText(CatboxApi.API_URL)
            isEnabled = false
        }

        val urlSection = label("URL Upload")
        urlInput = editText("https://example.com/file.jpg", password = false)

        val urlUpload = Button(this).apply {
            text = "Upload URL"
            setOnClickListener { uploadUrl() }
        }

        val historyLabel = label("My Uploads")
        val historyNote = TextView(this).apply {
            text = "This list is the app's local upload history. The official Catbox API documentation does not provide an account-file-list request."
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        }

        filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                filesContainer,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(title)
        root.addView(status)
        root.addView(upload, matchParams(dp(8)))
        root.addView(userHash, matchParams(dp(8)))
        root.addView(saveHash)
        root.addView(apiLabel, matchParams(dp(12)))
        root.addView(apiUrl, matchParams(dp(8)))
        root.addView(urlSection, matchParams(dp(12)))
        root.addView(urlInput, matchParams(dp(8)))
        root.addView(urlUpload, matchParams(dp(8)))
        root.addView(historyLabel, matchParams(dp(14)))
        root.addView(historyNote)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun loadSettings() {
        prefs.getString(KEY_USERHASH, "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let {
                userHash.setText(it)
                saveHash.isChecked = true
            }

        saveHash.setOnCheckedChangeListener { _, checked ->
            if (checked) saveCurrentUserHash()
            else prefs.edit().remove(KEY_USERHASH).apply()
        }
    }

    private fun saveCurrentUserHash() {
        val value = userHash.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            prefs.edit().remove(KEY_USERHASH).apply()
        } else {
            prefs.edit().putString(KEY_USERHASH, value).apply()
        }
    }

    private fun uploadAll(uris: List<Uri>) {
        if (saveHash.isChecked) saveCurrentUserHash()

        lifecycleScope.launch {
            var completed = 0
            var failed = 0
            val hash = userHash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

            try {
                uris.forEachIndexed { index, uri ->
                    status.text = "Uploading " + (index + 1) + "/" + uris.size + "..."
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val name = contentName(uri)
                            val file = copyToCache(uri, name)
                            try {
                                api.upload(file, hash).getOrThrow()
                            } finally {
                                file.delete()
                            }
                        }
                    }

                    result.onSuccess { url ->
                        completed++
                        saveUploadHistory(contentName(uri), url)
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        failed++
                        addErrorRow(
                            "Upload error: " + (error.message ?: error.javaClass.simpleName)
                        )
                    }
                }

                renderUploadHistory()
                status.text = "Done: " + completed + " uploaded, " + failed + " failed"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                status.text = "Upload error: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun uploadUrl() {
        if (saveHash.isChecked) saveCurrentUserHash()

        val url = urlInput.text?.toString()?.trim().orEmpty()
        val hash = userHash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        if (url.isBlank()) {
            status.text = "Enter a URL first"
            return
        }

        lifecycleScope.launch {
            status.text = "Uploading URL..."
            val result = withContext(Dispatchers.IO) {
                runCatching { api.uploadUrl(url, hash).getOrThrow() }
            }

            result.onSuccess { uploadedUrl ->
                saveUploadHistory(guessName(uploadedUrl), uploadedUrl)
                renderUploadHistory()
                urlInput.text?.clear()
                status.text = "URL upload complete"
            }.onFailure { error ->
                addErrorRow(
                    "URL upload error: " + (error.message ?: error.javaClass.simpleName)
                )
                status.text = "URL upload failed"
            }
        }
    }

    private fun saveUploadHistory(name: String, url: String) {
        val safeName = name.ifBlank { "upload.bin" }
        val current = readHistory().toMutableList()
        current.removeAll { it.second == url }
        current.add(0, safeName to url)
        writeHistory(current.take(MAX_HISTORY))
    }

    private fun readHistory(): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_HISTORY, "[]").orEmpty()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                        add(name to url)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeHistory(items: List<Pair<String, String>>) {
        val array = org.json.JSONArray()
        items.forEach { (name, url) ->
            array.put(
                org.json.JSONObject()
                    .put("name", name)
                    .put("url", url)
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun loadUploadHistory() {
        renderUploadHistory()
    }

    private fun renderUploadHistory() {
        filesContainer.removeAllViews()
        val history = readHistory()

        if (history.isEmpty()) {
            addInfoRow("No uploads saved on this device.")
            return
        }

        history.forEach { (name, url) ->
            addFileRow(name, url)
        }
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

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val download = Button(this).apply {
            text = "Download"
            setOnClickListener {
                enqueueDownload(name, url)
            }
        }

        val delete = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                deleteUpload(name, url)
            }
        }

        actions.addView(
            download,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            delete,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(title)
        row.addView(link, matchParams(dp(4)))
        row.addView(actions, matchParams(dp(4)))
        filesContainer.addView(row)
    }

    private fun deleteUpload(name: String, url: String) {
        val hash = userHash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (hash == null) {
            status.text = "Userhash is required for Catbox file deletion"
            return
        }

        val remoteFileName = url.substringAfterLast('/').substringBefore('?').trim()
        if (remoteFileName.isBlank()) {
            status.text = "Cannot determine the Catbox file name"
            return
        }

        lifecycleScope.launch {
            status.text = "Deleting " + name + "..."
            val result = withContext(Dispatchers.IO) {
                api.deleteFiles(listOf(remoteFileName), hash)
            }

            result.onSuccess {
                val updated = readHistory().filterNot { it.second == url }
                writeHistory(updated)
                renderUploadHistory()
                status.text = "Deleted: " + name
            }.onFailure { error ->
                status.text = "Delete error: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun addInfoRow(message: String) {
        val info = TextView(this).apply {
            text = message
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        filesContainer.addView(info)
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
                .filter { it.code >= 32 && it.code != 92 && it !in charArrayOf('/', ':', '*', '?', '"', '<', '>', '|') }
                .take(180)
                .ifBlank { "download.bin" }

            val extension = safeName.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setDescription("Downloading from Catbox")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType(mime)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    safeName
                )

            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            status.text = "Download started: " + safeName
        }.onFailure {
            status.text = "Download error: " + (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val safeName = name
            .filter { it.code >= 32 && it.code != 92 && it != '/' }
            .take(180)
            .ifBlank { "upload.bin" }

        val file = File.createTempFile("catbox_", "_$safeName", cacheDir)
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open selected file")

        input.use { source ->
            file.outputStream().use { target ->
                source.copyTo(target)
            }
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
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
            ?: URLDecoder.decode(uri.lastPathSegment.orEmpty(), "UTF-8")
                .substringAfterLast('/')
                .ifBlank { "upload.bin" }
    }

    private fun guessName(url: String): String {
        val raw = url.substringAfterLast('/').substringBefore('?')
            .ifBlank { "download.bin" }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }
            .getOrDefault(raw)
            .ifBlank { "download.bin" }
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
        ).apply {
            topMargin = top
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "hyouka_catbox"
        private const val KEY_USERHASH = "saved_userhash"
        private const val KEY_HISTORY = "local_upload_history"
        private const val MAX_HISTORY = 100
    }
}
