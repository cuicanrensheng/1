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
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Media3-Player")
                .header("Accept", "*/*")
                .header("Connection", "close")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun getChannelList(sourceUrl: String): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", "Media3-Player")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return list
            val lines = body.lines()

            var currentName = ""

            lines.forEach { line ->
                val l = line.trim()

                if (l.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentName = l.split(",").lastOrNull()?.trim() ?: "未知频道"
                }

                if (isValidUrl(l)) {
                    val finalUrl = getFinalRedirectUrl(l)
                    list.add(Channel(currentName, finalUrl))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase()
        return url.startsWith("http", ignoreCase = true) &&
                (lower.contains("m3u8") ||
                        lower.contains("live") ||
                        lower.contains("huya.com") ||
                        lower.contains(".mp4") ||
                        lower.contains(".ts"))
    }

    private fun getFinalRedirectUrl(url: String): String {
        return try {
            var currentUrl = url
            repeat(10) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Media3-Player")
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

    private fun resolveRedirect(original: String, location: String): String {
        if (location.startsWith("http")) return location
        val originalHttpUrl = original.toHttpUrlOrNull() ?: return original
        return originalHttpUrl.newBuilder()
            .encodedPath(location)
            .build()
            .toString()
    }
}
