package com.tomasthrawat.hyoukacatbox

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var filesView: TextView
    private lateinit var hash: EditText
    private val api = CatboxApi

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
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        filesView = findViewById(R.id.files)
        hash = findViewById(R.id.userhash)

        findViewById<Button>(R.id.upload).setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.list).setOnClickListener {
            loadFiles()
        }
    }

    private fun uploadAll(uris: List<Uri>) {
        lifecycleScope.launch {
            var completed = 0
            var failed = 0

            for ((index, uri) in uris.withIndex()) {
                if (isFinishing || isDestroyed) return@launch
                status.text = "Uploading ${index + 1}/${uris.size}..."

                val result = withContext(Dispatchers.IO) {
                    val fileResult = runCatching { copyToCache(uri) }
                    fileResult.fold(
                        onSuccess = { file ->
                            try {
                                api.upload(
                                    file,
                                    hash.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                                )
                            } finally {
                                file.delete()
                            }
                        },
                        onFailure = { Result.failure<String>(it) }
                    )
                }

                result.onSuccess {
                    completed++
                    appendResult(it)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    failed++
                    appendResult("Error: ${error.message ?: error.javaClass.simpleName}")
                }
            }

            status.text = "Done: $completed uploaded, $failed failed"
        }
    }

    private fun loadFiles() {
        val h = hash.text?.toString()?.trim().orEmpty()
        if (h.isBlank()) {
            status.text = "Enter your Catbox userhash"
            return
        }

        lifecycleScope.launch {
            status.text = "Loading..."

            val result = withContext(Dispatchers.IO) {
                api.files(h)
            }

            result.onSuccess { list ->
                filesView.text = if (list.isEmpty()) "No files found" else list.joinToString("\n\n")
                status.text = "${list.size} files"
            }.onFailure { error ->
                if (error is CancellationException) throw error
                status.text = "Error: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = contentName(uri)
        val file = File.createTempFile("catbox_", "_$name", cacheDir)

        val input = contentResolver.openInputStream(uri)
            ?: run {
                file.delete()
                throw IOException("Unable to open selected file")
            }

        input.use { source ->
            file.outputStream().use { output ->
                source.copyTo(output)
            }
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
        return name.filter { it.code >= 32 && it !in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') }
            .take(180)
            .ifBlank { "upload.bin" }
    }

    private fun appendResult(text: String) {
        filesView.append("\n$text")
    }
}
