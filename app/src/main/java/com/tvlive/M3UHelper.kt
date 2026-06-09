package com.tvlive

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

object M3UHelper {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)         // 支持重定向
        .followSslRedirects(true)       // 支持 HTTPS 重定向
        .retryOnConnectionFailure(true)
        .hostnameVerifier { _, _ -> true } // 忽略证书问题（支持全部 HTTPS）
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "ExoPlayer") // UA = ExoPlayer
                .header("Accept", "*/*")
                .header("Connection", "close")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .build()

    private const val M3U_URL = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u"

    suspend fun getChannelList(): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(M3U_URL)
                .header("User-Agent", "ExoPlayer")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return list
            val lines = body.lines()

            var currentName = ""

            lines.forEach { line ->
                val l = line.trim()

                // 解析频道名
                if (l.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentName = l.split(",").lastOrNull()?.trim() ?: "未知频道"
                }

                // 匹配所有有效直播源
                if (isValidUrl(l)) {
                    val finalUrl = getFinalRedirectUrl(l) // 自动追 10 次重定向
                    list.add(Channel(currentName, finalUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // 判断是否为有效播放地址
    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase()
        return url.startsWith("http", ignoreCase = true) &&
               (lower.contains("m3u8") ||
                lower.contains("live") ||
                lower.contains("huya.com") ||  // 虎牙
                lower.contains("youtube.com") ||
                lower.contains(".mp4") ||
                lower.contains(".ts"))
    }

    // 自动获取最终重定向地址（支持 10 次 301/302/307）
    private fun getFinalRedirectUrl(url: String): String {
        return try {
            var currentUrl = url
            repeat(10) { // 最多追 10 次
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "ExoPlayer")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location") ?: return currentUrl
                        currentUrl = resolveRedirect(currentUrl, location)
                    } else {
                        return currentUrl
                    }
                }
            }
            currentUrl
        } catch (e: Exception) {
            url
        }
    }

    // 处理相对路径重定向
    private fun resolveRedirect(original: String, location: String): String {
        if (location.startsWith("http")) return location
        val originalHttpUrl = original.toHttpUrlOrNull() ?: return original
        return originalHttpUrl.newBuilder()
            .encodedPath(location)
            .build()
            .toString()
    }
}
