package com.tvlive

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object M3UHelper {

    // 信任所有 SSL 证书（解决 GitHub 访问失败）
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(object : X509TrustManager {
                    override fun checkClientTrusted(p0: Array<X509Certificate>?, p1: String?) {}
                    override fun checkServerTrusted(p0: Array<X509Certificate>?, p1: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), null)
            }.socketFactory,
            object : X509TrustManager {
                override fun checkClientTrusted(p0: Array<X509Certificate>?, p1: String?) {}
                override fun checkServerTrusted(p0: Array<X509Certificate>?, p1: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        .hostnameVerifier { _, _ -> true }
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Android TV)")
                .header("Accept", "*/*")
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun getChannelList(sourceUrl: String): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", "Mozilla/5.0 (Android TV)")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return list
            val lines = body.lines()

            var currentName = "未知频道"

            // 逐行解析 M3U
            for (line in lines) {
                val l = line.trim()

                // 解析频道名
                if (l.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentName = l.split(",").lastOrNull()?.trim() ?: "未知频道"
                }

                // 解析播放地址（不再过滤！只要是 http 就认！）
                if (isValidUrl(l)) {
                    val finalUrl = getFinalRedirectUrl(l)
                    val urlList = mutableListOf(finalUrl)
                    list.add(Channel(currentName, urlList, 0, false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // 🔥 关键修复：不再过滤任何直播地址！
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http", ignoreCase = true)
    }

    private fun getFinalRedirectUrl(url: String): String {
        return try {
            var currentUrl = url
            repeat(10) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android TV)")
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
