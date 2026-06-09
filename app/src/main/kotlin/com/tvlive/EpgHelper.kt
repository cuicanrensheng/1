package com.tvlive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

data class EpgProgram(
    val channelName: String,
    val title: String,
    val startTime: Long,
    val endTime: Long
)

object EpgHelper {
    // 和 M3UHelper 一致的 OkHttp（超时+SSL容错）
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .hostnameVerifier { _, _ -> true }
        .build()

    // 标准 XMLTV 时间格式：yyyyMMddHHmmss Z（适配 catvod EPG）
    private val epgSdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC") // EPG 是 UTC 时间
    }

    suspend fun loadEpg(xmlUrl: String): MutableList<EpgProgram> {
        val list = mutableListOf<EpgProgram>()
        try {
            val req = Request.Builder()
                .url(xmlUrl)
                .header("User-Agent", "Mozilla/5.0 (Android TV)")
                .build()

            val resp = client.newCall(req).execute()
            val byteArr = resp.body?.bytes() ?: return list
            var input: InputStream = ByteArrayInputStream(byteArr)
            if (xmlUrl.endsWith(".gz", ignoreCase = true)) {
                input = GZIPInputStream(input)
            }

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")

            var eventType = parser.eventType
            var currChan = ""
            var currTitle = ""
            var startTs = 0L
            var endTs = 0L

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> currChan = parser.getAttributeValue(null, "id")
                            "programme" -> {
                                val startStr = parser.getAttributeValue(null, "start") ?: ""
                                val stopStr = parser.getAttributeValue(null, "stop") ?: ""
                                startTs = parseEpgTime(startStr)
                                endTs = parseEpgTime(stopStr)
                            }
                            "title" -> currTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme") {
                            if (startTs > 0 && endTs > startTs && currTitle.isNotEmpty()) {
                                list.add(EpgProgram(currChan, currTitle, startTs, endTs))
                            }
                            currTitle = ""
                            startTs = 0
                            endTs = 0
                        }
                    }
                }
                eventType = parser.next()
            }

            // 成功：加入EPG源列表
            val epgSet = AppConfig.epgSources.toMutableSet()
            epgSet.add(xmlUrl)
            AppConfig.epgSources = epgSet

        } catch (e: Exception) {
            e.printStackTrace()
            // 失败：不删除源，只打日志（更稳）
        }
        return list
    }

    // 核心修复：正确解析 XMLTV 时间 → 北京时间戳
    private fun parseEpgTime(timeStr: String): Long {
        return try {
            if (timeStr.length < 14) return 0L
            // 补全时区：catvod EPG 格式是 yyyyMMddHHmmss+0800
            val fixedStr = if (timeStr.contains("+") || timeStr.contains("-")) {
                timeStr
            } else {
                "$timeStr +0800"
            }
            epgSdf.parse(fixedStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // 获取当前正在播放节目
    fun getNowProgram(epgList: List<EpgProgram>, chanName: String): EpgProgram? {
        val now = System.currentTimeMillis()
        return epgList.firstOrNull {
            it.channelName.equals(chanName, ignoreCase = true)
                    && now in it.startTime..it.endTime
        }
    }

    // 获取今日所有节目
    fun getTodayAllProgram(epgList: List<EpgProgram>, chanName: String): List<EpgProgram> {
        val nowDay = System.currentTimeMillis() / (1000 * 60 * 60 * 24)
        return epgList.filter {
            it.channelName.equals(chanName, ignoreCase = true)
                    && it.startTime / (1000 * 60 * 60 * 24) == nowDay
        }.sortedBy { it.startTime }
    }
}
