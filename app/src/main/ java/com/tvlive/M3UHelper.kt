package com.tvlive

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

object M3UHelper {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .hostnameVerifier { _, _ -> true }
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "ExoPlayer")
                .header("Accept", "*/*")
                .header("Connection", "close")
                .build()
            chain.proceed(req)
        }
        .build()

    // 解析m3u/tvbox 支持多线路（逗号分隔同频道多url）
    suspend fun parseM3u(url: String): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", "ExoPlayer").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return list
            val lines = body.lines()
            var currName = ""
            lines.forEach { l ->
                val line = l.trim()
                if(line.startsWith("#EXTINF:", ignoreCase = true)){
                    currName = line.split(",").lastOrNull()?.trim() ?: "未知频道"
                }
                if(isPlayUrl(line)){
                    val finalUrl = getFinalRedirectUrl(line)
                    // 多线路分割
                    val urls = finalUrl.split(",").filter { it.isNotBlank() }.toMutableList()
                    val fav = FavoriteManager.isFav(currName)
                    list.add(Channel(name = currName, streamUrls = urls, isFavorite = fav))
                }
            }
            // 拉取成功存入历史订阅源
            val sources = AppConfig.m3uSources.toMutableSet()
            sources.add(url)
            AppConfig.m3uSources = sources
        }catch (e: Exception){
            // 拉取失败移除该订阅源
            val sources = AppConfig.m3uSources.toMutableSet()
            sources.remove(url)
            AppConfig.m3uSources = sources
            e.printStackTrace()
        }
        return list
    }

    private fun isPlayUrl(url: String): Boolean {
        val low = url.lowercase()
        return url.startsWith("http") && (low.contains("m3u8") || low.contains("huya.com") || low.contains("ts") || low.contains("mp4") || low.contains("live"))
    }

    // 最多10次301/302重定向
    private fun getFinalRedirectUrl(url: String): String {
        var curr = url
        repeat(10){
            val req = Request.Builder().url(curr).header("UA", "ExoPlayer").build()
            client.newCall(req).execute().use { res ->
                if(res.isRedirect){
                    val loc = res.header("Location") ?: return curr
                    curr = resolveRelativeRedirect(curr, loc)
                }else return curr
            }
        }
        return curr
    }

    private fun resolveRelativeRedirect(origin: String, loc: String): String {
        if(loc.startsWith("http")) return loc
        val httpUrl = origin.toHttpUrlOrNull() ?: return origin
        return httpUrl.newBuilder().encodedPath(loc).build().toString()
    }
}
