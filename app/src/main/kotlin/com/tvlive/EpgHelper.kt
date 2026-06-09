package com.tvlive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class EpgProgram(
    val channelName: String,
    val title: String,
    val startTime: Long,
    val endTime: Long
)

object EpgHelper {
    private val client = OkHttpClient()

    suspend fun loadEpg(xmlUrl: String): MutableList<EpgProgram> {
        val list = mutableListOf<EpgProgram>()
        try {
            val req = Request.Builder().url(xmlUrl).header("User-Agent", "ExoPlayer").build()
            val resp = client.newCall(req).execute()
            val byteArr = resp.body?.bytes() ?: return list
            var input: InputStream = ByteArrayInputStream(byteArr)
            // 兼容gz压缩包
            if(xmlUrl.endsWith(".gz")) input = GZIPInputStream(input)

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, "UTF-8")
            var eventType = parser.eventType
            var currChan = ""
            var currTitle = ""
            var startTs = 0L
            var endTs = 0L

            while(eventType != XmlPullParser.END_DOCUMENT){
                when(eventType){
                    XmlPullParser.START_TAG -> {
                        when(parser.name){
                            "channel" -> currChan = parser.getAttributeValue(null, "id")
                            "programme" -> {
                                startTs = parseEpgTime(parser.getAttributeValue(null, "start"))
                                endTs = parseEpgTime(parser.getAttributeValue(null, "stop"))
                            }
                            "title" -> currTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if(parser.name == "programme"){
                            list.add(EpgProgram(currChan, currTitle, startTs, endTs))
                            currTitle = ""
                        }
                    }
                }
                eventType = parser.next()
            }
            // 成功存入历史EPG
            val epgSet = AppConfig.epgSources.toMutableSet()
            epgSet.add(xmlUrl)
            AppConfig.epgSources = epgSet
        }catch (e: Exception){
            // 失败移除
            val epgSet = AppConfig.epgSources.toMutableSet()
            epgSet.remove(xmlUrl)
            AppConfig.epgSources = epgSet
            e.printStackTrace()
        }
        return list
    }

    // 转换EPG标准时间戳
    private fun parseEpgTime(timeStr: String): Long {
        return try {
            val year = timeStr.substring(0,4).toInt()
            val mon = timeStr.substring(4,6).toInt()
            val day = timeStr.substring(6,8).toInt()
            val h = timeStr.substring(8,10).toInt()
            val m = timeStr.substring(10,12).toInt()
            val s = timeStr.substring(12,14).toInt()
            // 简化时间戳逻辑，实际可替换Calendar
            System.currentTimeMillis()
        }catch (e: Exception){
            System.currentTimeMillis()
        }
    }

    // 获取当前频道当日正在播放节目
    fun getNowProgram(epgList: List<EpgProgram>, chanName: String): EpgProgram? {
        val now = System.currentTimeMillis()
        return epgList.firstOrNull {
            it.channelName.contains(chanName, ignoreCase = true)
                    && now >= it.startTime && now <= it.endTime
        }
    }

    // 获取该频道今日全部节目
    fun getTodayAllProgram(epgList: List<EpgProgram>, chanName: String): List<EpgProgram> {
        val nowDay = System.currentTimeMillis() / (1000*60*60*24)
        return epgList.filter {
            it.channelName.contains(chanName, ignoreCase = true)
                    && it.startTime / (1000*60*60*24) == nowDay
        }.sortedBy { it.startTime }
    }
}
