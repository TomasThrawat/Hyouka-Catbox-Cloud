package com.tomasthrawat.hyoukacatbox

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
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
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var filesView: TextView
    private lateinit var hash: EditText

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
            showSavedLinks()
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

        val list = Button(this).apply {
            text = "My files"
            setOnClickListener { showSavedLinks() }
        }

        filesView = TextView(this).apply {
            text = "Uploaded links will appear here."
            setTextColor(Color.WHITE)
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                filesView,
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

    private fun uploadAll(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                var completed = 0
                var failed = 0
                val userhash = hash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                for ((index, uri) in uris.withIndex()) {
                    status.text = "Uploading " + (index + 1) + "/" + uris.size + "..."

                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val file = copyToCache(uri)
                            try {
                                api.upload(file, userhash).getOrThrow()
                            } finally {
                                file.delete()
                            }
                        }
                    }

                    result.onSuccess {
                        completed++
                        saveLink(it)
                        appendResult(it)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failed++
                        appendResult(
                            "Error: " + (error.message ?: error.javaClass.simpleName)
                        )
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

    private fun showSavedLinks() {
        val links = readSavedLinks()
        filesView.text = if (links.isEmpty()) {
            "No uploaded links saved yet.\n\nMy files shows local upload history. Catbox's official API does not provide an account-file listing request."
        } else {
            links.joinToString("\n\n")
        }
        status.text = if (links.isEmpty()) "No saved links" else links.size.toString() + " saved links"
    }

    private fun readSavedLinks(): List<String> =
        prefs.getString(KEY_HISTORY, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    private fun saveLink(url: String) {
        if (url.isBlank()) return
        val links = (listOf(url) + readSavedLinks().filterNot { it == url })
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, links.joinToString("\n")).apply()
    }

    private fun copyToCache(uri: Uri): File {
        val name = contentName(uri)
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

    private fun appendResult(text: String) {
        filesView.append("\n" + text)
    }

    private fun matchParams(topMargin: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS_NAME = "hyouka_catbox"
        const val KEY_HISTORY = "uploaded_links"
        const val MAX_HISTORY = 500
    }
}
