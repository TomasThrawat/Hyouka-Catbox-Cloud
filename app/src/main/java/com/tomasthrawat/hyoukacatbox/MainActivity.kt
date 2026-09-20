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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var filesView: TextView
    private lateinit var hash: EditText
    private val api = CatboxApi

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) uploadAll(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        filesView = findViewById(R.id.files)
        hash = findViewById(R.id.userhash)
        findViewById<Button>(R.id.upload).setOnClickListener { picker.launch("*/*") }
        findViewById<Button>(R.id.list).setOnClickListener { loadFiles() }
    }

    private fun uploadAll(uris: List<Uri>) {
        lifecycleScope.launch {
            uris.forEachIndexed { index, uri ->
                status.text = "Uploading ${index + 1}/${uris.size}..."
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        api.upload(copyToCache(uri), hash.text?.toString())
                    }.getOrElse { Result.failure<String>(it) }
                }
                result.onSuccess { url ->
                    filesView.append("\n$url")
                }.onFailure { error ->
                    filesView.append("\nError: ${error.message}")
                }
            }
            status.text = "Done"
        }
    }

    private fun loadFiles() {
        val h = hash.text?.toString().orEmpty()
        if (h.isBlank()) {
            status.text = "Enter your Catbox userhash"
            return
        }
        lifecycleScope.launch {
            status.text = "Loading..."
            val result = withContext(Dispatchers.IO) { api.files(h) }
            result.onSuccess { list ->
                filesView.text = list.joinToString("\n\n")
                status.text = "${list.size} files"
            }.onFailure { error ->
                status.text = "Error: ${error.message}"
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = contentName(uri)
        val file = File(cacheDir, "${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri)!!.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun contentName(uri: Uri): String {
        var name = "upload.bin"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        return name
    }
}
