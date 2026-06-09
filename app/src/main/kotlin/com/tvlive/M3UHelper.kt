package com.tvlive

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

object M3UHelper {

    // 配置 OkHttp 客户端（用于网络请求）
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)   // 连接超时
        .readTimeout(15, TimeUnit.SECONDS)      // 读取超时
        .writeTimeout(15, TimeUnit.SECONDS)     // 写入超时
        .followRedirects(true)                  // 允许重定向
        .followSslRedirects(true)               // 允许 SSL 重定向
        .retryOnConnectionFailure(true)         // 连接失败自动重试
        .hostnameVerifier { _, _ -> true }      // 信任所有证书（解决 https 问题）
        .addInterceptor { chain ->
            // 统一添加请求头
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

    /**
     * 从网络获取 M3U 频道列表
     * @param sourceUrl 订阅地址
     * @return 频道列表（MutableList<Channel>）
     */
    suspend fun getChannelList(sourceUrl: String): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        try {
            // 构建网络请求
            val request = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", "Media3-Player")
                .build()

            // 执行请求并获取返回数据
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return list
            val lines = body.lines()

            var currentName = ""

            // 逐行解析 M3U
            lines.forEach { line ->
                val l = line.trim()

                // 解析频道名称
                if (l.startsWith("#EXTINF:", ignoreCase = true)) {
                    currentName = l.split(",").lastOrNull()?.trim() ?: "未知频道"
                }

                // 解析播放地址
                if (isValidUrl(l)) {
                    val finalUrl = getFinalRedirectUrl(l)

                    // ======================
                    // 🔥 核心修复：多线路适配
                    // 把单个地址包装成 MutableList<String>
                    // ======================
                    val urlList = mutableListOf(finalUrl)

                    // 构造 Channel 对象（4个参数：名称、多线路、当前线路索引、是否收藏）
                    list.add(Channel(currentName, urlList, 0, false))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * 判断是否为合法的直播地址
     */
    private fun isValidUrl(url: String): Boolean {
        val lower = url.lowercase()
        return url.startsWith("http", ignoreCase = true) &&
                (lower.contains("m3u8") ||
                        lower.contains("live") ||
                        lower.contains("huya.com") ||
                        lower.contains(".mp4") ||
                        lower.contains(".ts"))
    }

    /**
     * 递归获取最终重定向地址（解决 301/302 跳转）
     */
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

    /**
     * 处理相对路径重定向
     */
    private fun resolveRedirect(original: String, location: String): String {
        if (location.startsWith("http")) return location
        val originalHttpUrl = original.toHttpUrlOrNull() ?: return original
        return originalHttpUrl.newBuilder()
            .encodedPath(location)
            .build()
            .toString()
    }
}
