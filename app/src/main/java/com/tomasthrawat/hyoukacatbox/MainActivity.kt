package com.tomasthrawat.hyoukacatbox
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity:AppCompatActivity(){
 private lateinit var status:TextView
 private lateinit var filesView:TextView
 private lateinit var hash:EditText
 private val api=CatboxApi()
 private val picker=registerForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->if(uris.isNotEmpty())uploadAll(uris)}
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)
  status=findViewById(R.id.status);filesView=findViewById(R.id.files);hash=findViewById(R.id.userhash)
  findViewById<Button>(R.id.upload).setOnClickListener{picker.launch("*/*")}
  findViewById<Button>(R.id.list).setOnClickListener{loadFiles()}
 }
 private fun uploadAll(uris:List<Uri>){lifecycleScope.launch{
  uris.forEachIndexed{index,uri->
   status.text="Uploading "+(index+1)+"/"+uris.size+"..."
   runCatching{api.upload(copyToCache(uri),hash.text?.toString())}.onSuccess{url->filesView.append("\n"+url)}.onFailure{e->filesView.append("\nError: "+e.message)}
  };status.text="Done"
 }}
 private fun loadFiles(){val h=hash.text?.toString().orEmpty();if(h.isBlank()){status.text="Enter your Catbox userhash";return}
  lifecycleScope.launch{status.text="Loading..."
   runCatching{api.files(h)}.onSuccess{list->filesView.text=list.joinToString("\n\n"){it.name+" • "+it.size+" bytes\n"+it.url};status.text=list.size.toString()+" files"}.onFailure{e->status.text="Error: "+e.message}
  }
 }
 private fun copyToCache(uri:Uri):File{val name=contentName(uri);val f=File(cacheDir,System.currentTimeMillis().toString()+"_"+name)
  contentResolver.openInputStream(uri)!!.use{input->f.outputStream().use{output->input.copyTo(output)}};return f
 }
 private fun contentName(uri:Uri):String{var n="upload.bin";contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())n=it.getString(0)};return n}
}