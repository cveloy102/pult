package ru.hl100.pult

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AndroidRelease(val version:String,val notes:String,val url:String,val digest:String?)
object AndroidUpdates {
    private const val REPOSITORY="cveloy102/pult"
    private fun version(value:String):List<Int>?=value.split('.').map { it.toIntOrNull() ?: return null }.takeIf{it.size==3}
    fun isNewer(candidate:String,current:String):Boolean {val a=version(candidate)?:return false;val b=version(current)?:return false;for(i in 0..2){if(a[i]!=b[i])return a[i]>b[i]};return false}
    fun find():AndroidRelease? {
        var newest:AndroidRelease?=null
        for(page in 1..100){
            val connection=URL("https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page").openConnection() as HttpURLConnection
            connection.connectTimeout=7000;connection.readTimeout=10000;connection.setRequestProperty("User-Agent","Pult/${BuildConfig.VERSION_NAME}")
            val list=try{if(connection.responseCode!=200)throw Exception("GitHub сейчас недоступен (${connection.responseCode}).");JSONArray(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
            for(i in 0 until list.length()){
                val release=list.getJSONObject(i);if(release.optBoolean("draft")||release.optBoolean("prerelease"))continue
                val tag=release.optString("tag_name");if(!tag.startsWith("android-v"))continue
                val number=tag.removePrefix("android-v");if(!isNewer(number,BuildConfig.VERSION_NAME)||newest!=null&&!isNewer(number,newest.version))continue
                val assets=release.getJSONArray("assets")
                for(j in 0 until assets.length()){
                    val asset=assets.getJSONObject(j);val name=asset.getString("name")
                    if(!name.endsWith(".apk")||name.contains("debug"))continue
                    val url=asset.getString("browser_download_url");if(!url.startsWith("https://github.com/$REPOSITORY/releases/download/"))continue
                    newest=AndroidRelease(number,release.optString("body"),url,asset.optString("digest").takeIf{it.startsWith("sha256:")})
                    break
                }
            }
            if(list.length()<100)return newest
        }
        throw Exception("Слишком много релизов. Проверь GitHub вручную.")
    }
    fun download(context:Context,release:AndroidRelease):File {
        require(release.url.startsWith("https://github.com/$REPOSITORY/releases/download/"))
        val directory=File(context.cacheDir,"updates").apply{mkdirs()};val partial=File(directory,"pult.apk.part");val destination=File(directory,"pult.apk")
        var url=URL(release.url)
        var redirects=0
        try {
            while(true){
                val connection=url.openConnection() as HttpURLConnection
                connection.connectTimeout=10000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
                try{
                    val response=connection.responseCode
                    if(response in listOf(301,302,303,307,308)){
                        if(++redirects>5)throw Exception("Слишком много перенаправлений.")
                        val next=URL(url,connection.getHeaderField("Location"))
                        require(next.protocol=="https"&&(next.host=="github.com"||next.host=="release-assets.githubusercontent.com"||next.host=="objects.githubusercontent.com")){"Неверный сервер обновления."}
                        url=next;continue
                    }
                    if(response!=200)throw Exception("Не удалось скачать APK ($response).")
                    var bytes=0L
                    connection.inputStream.use{input->partial.outputStream().use{output->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;bytes+=n;if(bytes>200L*1024*1024)throw Exception("Пакет обновления слишком велик.");output.write(buffer,0,n)}}}
                    break
                }finally{connection.disconnect()}
            }
            release.digest?.let{digest->val hash=MessageDigest.getInstance("SHA-256");partial.inputStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}};val actual=hash.digest().joinToString(""){"%02x".format(it)};require("sha256:$actual"==digest){"Файл обновления повреждён."}}
            val manager=context.packageManager
            val archive=manager.getPackageArchiveInfo(partial.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:throw Exception("Некорректный APK.")
            val installed=manager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            require(archive.packageName==context.packageName&&archive.longVersionCode>installed.longVersionCode){"APK не подходит для обновления Пульта."}
            val old=installed.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.sorted()
            val next=archive.signingInfo?.apkContentsSigners?.map{it.toCharsString()}?.sorted()
            require(!old.isNullOrEmpty()&&old==next){"Подпись APK отличается. Обновление не установлено."}
            if(destination.exists()&&!destination.delete())throw Exception("Не удалось заменить скачанный APK.")
            if(!partial.renameTo(destination))throw Exception("Не удалось сохранить APK.")
            return destination
        }finally{partial.delete()}
    }
    fun install(context:Context,apk:File){
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
