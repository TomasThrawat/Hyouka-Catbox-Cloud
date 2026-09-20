package com.tomasthrawat.hyoukacatbox
import okhttp3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CatFile(val name:String,val size:Long,val url:String)

class CatboxApi {
 private val client=OkHttpClient()
 suspend fun upload(file:File,userhash:String?):String=withContext(Dispatchers.IO){
  val b=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("reqtype","fileupload")
   .addFormDataPart("fileToUpload",file.name,file.asRequestBody("application/octet-stream".toMediaType()))
  if(!userhash.isNullOrBlank()) b.addFormDataPart("userhash",userhash)
  val r=client.newCall(Request.Builder().url("https://catbox.moe/user/api.php").post(b.build()).build()).execute()
  if(!r.isSuccessful) error("HTTP "+r.code)
  r.body?.string()?.trim().orEmpty().also{if(!it.startsWith("http")) error(it)}
 }
 suspend fun files(userhash:String):List<CatFile>=withContext(Dispatchers.IO){
  val body=FormBody.Builder().add("reqtype","getaccountfiles").add("userhash",userhash).build()
  val r=client.newCall(Request.Builder().url("https://catbox.moe/user/api.php").post(body).build()).execute()
  if(!r.isSuccessful) error("HTTP "+r.code)
  r.body?.string().orEmpty().lines().filter{it.isNotBlank()}.mapNotNull{line->
   val p=line.split("\s+".toRegex(),limit=3)
   if(p.size>=3) CatFile(p[1],p[2].toLongOrNull()?:0,p[0]) else null
  }
 }
}