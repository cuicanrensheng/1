package com.tvlive

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class EpgProgram(
    val title: String,
    val startTime: Long,
    val endTime: Long
)

object EpgHelper {
    fun parseXml(xmlContent: String): List<EpgProgram> {
        val list = mutableListOf<EpgProgram>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var currentTitle = ""
            var start = 0L
            var end = 0L

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        if (tag == "programme") {
                            start = parser.getAttributeValue(null, "start")?.toLongOrNull() ?: 0
                            end = parser.getAttributeValue(null, "stop")?.toLongOrNull() ?: 0
                        }
                        if (tag == "title") {
                            currentTitle = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme") {
                            list.add(EpgProgram(currentTitle, start, end))
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
