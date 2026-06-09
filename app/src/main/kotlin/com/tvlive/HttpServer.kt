package com.tvlive

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.InputStream

class HttpSettingServer(
    private val context: Context,  // 关键修复：从外部传入 Context
    port: Int = 10481
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // 网页静态资源
        if (uri == "/") {
            return try {
                val html: InputStream = context.assets.open("web/index.html")
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html",
                    html.readBytes().inputStream(),
                    html.available().toLong()
                )
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }

        // 接口：保存订阅源
        if (uri.startsWith("/setM3u")) {
            val params = session.parameters
            val m3u = params["url"]?.firstOrNull() ?: ""
            AppConfig.currentM3u = m3u
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }

        // 接口：保存EPG
        if (uri.startsWith("/setEpg")) {
            val epg = session.parameters["url"]?.firstOrNull() ?: ""
            AppConfig.currentEpg = epg
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }

        // 缓存时长设置
        if (uri.startsWith("/setCache")) {
            val min = session.parameters["min"]?.firstOrNull()?.toIntOrNull() ?: 60
            AppConfig.cacheMinute = min
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
    }
}
