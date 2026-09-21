package com.tomasthrawat.hyoukacatbox

import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLConnection

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var filesContainer: LinearLayout
    private lateinit var hash: EditText
    private lateinit var saveHash: CheckBox

    private val api = CatboxApi
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            status.text = "No files selected"
            return@registerForActivityResult
        }
        uploadAll(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
            loadSavedHash()
            showSavedFiles()
        } catch (t: Throwable) {
            val fallback = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                textSize = 16f
                setPadding(dp(20), dp(20), dp(20), dp(20))
                text = "Hyouka Cloud failed to start\n\n" +
                    t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
            }
            setContentView(fallback)
        }
    }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        val title = TextView(this).apply {
            text = "Hyouka Cloud"
            setTextColor(Color.WHITE)
            textSize = 28f
        }

        status = TextView(this).apply {
            text = "Ready"
            setTextColor(Color.LTGRAY)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        }

        val upload = Button(this).apply {
            text = "Upload files"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }

        hash = EditText(this).apply {
            hint = "Catbox userhash (optional)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }

        saveHash = CheckBox(this).apply {
            text = "Save userhash on this device"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    saveCurrentHash()
                } else {
                    prefs.edit().remove(KEY_USERHASH).apply()
                    status.text = "Saved userhash removed"
                }
            }
        }

        val list = Button(this).apply {
            text = "My files"
            setOnClickListener { showSavedFiles() }
        }

        filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                filesContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(status)
        root.addView(upload, matchParams(dp(8)))
        root.addView(hash, matchParams(dp(8)))
        root.addView(saveHash, matchParams(dp(2)))
        root.addView(list, matchParams(dp(8)))
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

    private fun loadSavedHash() {
        val saved = prefs.getString(KEY_USERHASH, "").orEmpty()
        if (saved.isNotBlank()) {
            hash.setText(saved)
            saveHash.isChecked = true
        }
    }

    private fun saveCurrentHash() {
        val value = hash.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            prefs.edit().remove(KEY_USERHASH).apply()
            status.text = "Enter a userhash to save it"
        } else {
            prefs.edit().putString(KEY_USERHASH, value).apply()
            status.text = "Userhash saved"
        }
    }

    private fun uploadAll(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                if (saveHash.isChecked) {
                    saveCurrentHash()
                }

                var completed = 0
                var failed = 0
                val userhash = hash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                for ((index, uri) in uris.withIndex()) {
                    status.text = "Uploading " + (index + 1) + "/" + uris.size + "..."

                    val originalName = contentName(uri)
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val file = copyToCache(uri, originalName)
                            try {
                                api.upload(file, userhash).getOrThrow()
                            } finally {
                                file.delete()
                            }
                        }
                    }

                    result.onSuccess { url ->
                        completed++
                        saveFileEntry(originalName, url)
                        addFileRow(originalName, url, atTop = true)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failed++
                        addErrorRow("Error: " + (error.message ?: error.javaClass.simpleName))
                    }
                }

                status.text = "Done: " + completed + " uploaded, " + failed + " failed"
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                status.text = "Upload error: " + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun showSavedFiles() {
        filesContainer.removeAllViews()
        val entries = readSavedFiles()

        if (entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No uploaded files saved yet.\n\nMy files is local upload history."
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, dp(16), 0, 0)
            }
            filesContainer.addView(empty)
            status.text = "No saved files"
            return
        }

        entries.forEach { entry ->
            addFileRow(entry.name, entry.url, atTop = false)
        }
        status.text = entries.size.toString() + " saved files"
    }

    private fun addFileRow(name: String, url: String, atTop: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val nameView = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 2
        }

        val urlView = TextView(this).apply {
            text = url
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setTextIsSelectable(true)
            maxLines = 2
        }

        val download = Button(this).apply {
            text = "Download"
            setOnClickListener {
                enqueueDownload(name, url)
            }
        }

        row.addView(nameView)
        row.addView(urlView, matchParams(dp(4)))
        row.addView(download, matchParams(dp(4)))

        val divider = View(this).apply {
            setBackgroundColor(Color.DKGRAY)
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(
                divider,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            )
        }

        if (atTop && filesContainer.childCount > 0) {
            filesContainer.addView(wrapper, 0)
        } else {
            filesContainer.addView(wrapper)
        }
    }

    private fun addErrorRow(message: String) {
        val errorView = TextView(this).apply {
            text = message
            setTextColor(Color.RED)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        filesContainer.addView(errorView)
    }

    private fun enqueueDownload(fileName: String, url: String) {
        runCatching {
            val safeName = fileName
                .filter { it.code >= 32 && it !in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') }
                .take(180)
                .ifBlank { "download.bin" }

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setDescription("Downloading from Catbox")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType(
                    URLConnection.guessContentTypeFromName(safeName)
                        ?: "application/octet-stream"
                )
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

    private fun readSavedFiles(): List<FileEntry> {
        val raw = prefs.getString(KEY_HISTORY, "").orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        FileEntry(
                            item.optString("name").ifBlank { "download.bin" },
                            url
                        )
                    )
                }
            }
        }.getOrElse {
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .map { FileEntry("download.bin", it) }
                .toList()
        }
    }

    private fun saveFileEntry(name: String, url: String) {
        if (url.isBlank()) return

        val updated = buildList {
            add(FileEntry(name, url))
            addAll(readSavedFiles().filterNot { it.url == url })
        }.take(MAX_HISTORY)

        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("url", it.url)
            )
        }

        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val file = File.createTempFile("catbox_", "_" + name, cacheDir)

        val input = contentResolver.openInputStream(uri)
            ?: run {
                file.delete()
                throw IOException("Unable to open selected file")
            }

        input.use { source ->
            file.outputStream().use { output -> source.copyTo(output) }
        }

        if (!file.exists() || file.length() == 0L) {
            file.delete()
            throw IOException("Selected file is empty or could not be copied")
        }

        return file
    }

    private fun contentName(uri: Uri): String {
        var name = "upload.bin"
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    if (!value.isNullOrBlank()) name = value
                }
            }
        }

        return name.filter {
            it.code >= 32 && it !in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        }.take(180).ifBlank { "upload.bin" }
    }

    private fun matchParams(topMargin: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class FileEntry(val name: String, val url: String)

    private companion object {
        const val PREFS_NAME = "hyouka_catbox"
        const val KEY_HISTORY = "uploaded_files"
        const val KEY_USERHASH = "saved_userhash"
        const val MAX_HISTORY = 500
    }
}
