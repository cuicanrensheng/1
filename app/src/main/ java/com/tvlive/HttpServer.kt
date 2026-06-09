package com.tvlive

import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class HttpSettingServer(port: Int = 10481) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        // 网页静态资源
        if(uri == "/"){
            val html: InputStream = applicationContext.assets.open("web/index.html")
            return newFixedLengthResponse(Response.Status.OK, "text/html", html.readBytes().inputStream(), html.available().toLong())
        }
        // 接口：保存订阅源
        if(uri.startsWith("/setM3u")){
            val params = session.parameters
            val m3u = params["url"]?.firstOrNull() ?: ""
            AppConfig.currentM3u = m3u
            return newFixedLengthResponse("ok")
        }
        // 接口：保存EPG
        if(uri.startsWith("/setEpg")){
            val epg = session.parameters["url"]?.firstOrNull() ?: ""
            AppConfig.currentEpg = epg
            return newFixedLengthResponse("ok")
        }
        // 缓存时长设置
        if(uri.startsWith("/setCache")){
            val min = session.parameters["min"]?.firstOrNull()?.toIntOrNull() ?: 60
            AppConfig.cacheMinute = min
            return newFixedLengthResponse("ok")
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
    }
}
