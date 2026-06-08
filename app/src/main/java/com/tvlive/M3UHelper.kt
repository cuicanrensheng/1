package com.tvlive

import okhttp3.OkHttpClient
import okhttp3.Request

object M3UHelper {
    private val client = OkHttpClient()
    private const val M3U_URL = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u"

    suspend fun getChannelList(): MutableList<Channel> {
        val list = mutableListOf<Channel>()
        val req = Request.Builder().url(M3U_URL).build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return list
        val lines = body.split("\n")
        var name = ""
        lines.forEach { line ->
            val l = line.trim()
            if(l.startsWith("#EXTINF:")) name = l.split(",").last()
            if(l.startsWith("http") && l.contains("m3u8")) list.add(Channel(name, l))
        }
        return list
    }
}
