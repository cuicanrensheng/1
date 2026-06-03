package com.iptvlive.util;

import com.iptvlive.bean.EpgInfoBean;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * EPG下载+XML解析工具
 * 自动携带全局UA/Refer/Cookie
 * 下载xml保存本地epg_cache.xml
 * 解析programme、title标签生成节目实体
 */
public class EpgXmlParserUtil {
    //本地缓存文件名
    public static final String EPG_CACHE_NAME = "epg_cache.xml";

    //网络下载EPG文件
    public static boolean downloadEpgXml(String epgUrl, File saveFile) {
        HttpURLConnection conn = null;
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            URL url = new URL(epgUrl);
            conn = (HttpURLConnection) url.openConnection();
            String ua = HttpHeaderSpUtil.getUA();
            String ref = HttpHeaderSpUtil.getReferer();
            String ck = HttpHeaderSpUtil.getCookie();
            conn.setRequestProperty("User-Agent", ua);
            if (!ref.isEmpty()) conn.setRequestProperty("Referer", ref);
            if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != 200) {
                LogSpUtil.addParseLog("【EPG下载失败】链接：" + epgUrl + " 响应码：" + conn.getResponseCode());
                return false;
            }
            is = conn.getInputStream();
            fos = new FileOutputStream(saveFile);
            byte[] buf = new byte[2048];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            LogSpUtil.addParseLog("【EPG下载成功】" + epgUrl);
            return true;
        } catch (Exception e) {
            String err = android.util.Log.getStackTraceString(e);
            LogSpUtil.addParseLog("【EPG下载异常】" + epgUrl + "\n" + err);
            return false;
        } finally {
            try {
                if (fos != null) fos.close();
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignore) {}
        }
    }

    //解析本地EPG文件
    public static List<EpgInfoBean> parseLocalEpgFile(File xmlFile) {
        List<EpgInfoBean> epgList = new ArrayList<>();
        if (!xmlFile.exists()) return epgList;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(xmlFile), StandardCharsets.UTF_8));
            parser.setInput(br);
            int event = parser.getEventType();
            EpgInfoBean temp = null;
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if ("programme".equals(tag)) {
                            temp = new EpgInfoBean();
                            temp.channelId = parser.getAttributeValue(null, "channel");
                            temp.startTime = parser.getAttributeValue(null, "start");
                            temp.endTime = parser.getAttributeValue(null, "stop");
                        } else if ("title".equals(tag) && temp != null) {
                            temp.proName = parser.nextText();
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        if ("programme".equals(tag) && temp != null) {
                            epgList.add(temp);
                            temp = null;
                        }
                        break;
                }
                event = parser.next();
            }
            br.close();
            LogSpUtil.addParseLog("【EPG解析完成，节目总数：" + epgList.size() + "】");
        } catch (Exception e) {
            String err = android.util.Log.getStackTraceString(e);
            LogSpUtil.addParseLog("【EPG解析异常】" + err);
        }
        return epgList;
    }
}
