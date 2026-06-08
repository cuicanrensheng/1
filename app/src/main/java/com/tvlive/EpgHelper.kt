package com.tvlive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParserFactory

object EpgHelper {
    private val client = OkHttpClient()
    private const val EPG_URL = "https://epg.catvod.com/epg.xml"

    suspend fun loadEpg(): MutableList<EpgProgram> {
        val list = mutableListOf<EpgProgram>()
        val req = Request.Builder().url(EPG_URL).build()
        val resp = client.newCall(req).execute()
        val stream = resp.body?.byteStream() ?: return list
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, "UTF-8")
        var event = parser.eventType
        var cid = ""
        var title = ""
        var start = ""
        var stop = ""
        while(event != parser.END_DOCUMENT){
            when(event){
                parser.START_TAG -> {
                    when(parser.name){
                        "channel" -> cid = parser.getAttributeValue(null, "id")
                        "title" -> title = parser.nextText()
                        "start" -> start = parser.nextText()
                        "stop" -> stop = parser.nextText()
                    }
                }
                parser.END_TAG -> {
                    if(parser.name == "programme"){
                        list.add(EpgProgram(cid, title, start, stop))
                    }
                }
            }
            event = parser.next()
        }
        return list
    }
}
