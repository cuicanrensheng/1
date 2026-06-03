package com.iptvlive.util;

import com.iptvlive.bean.ChannelBean;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * M3U解析工具：解析#EXTINF标签 tvg-id、分辨率、码率、频道名
 * 自动携带全局UA/Refer/Cookie
 */
public class M3UParserUtil {
    public static List<ChannelBean> parseNetM3U(String m3uUrl) {
        List<ChannelBean> chList = new ArrayList<>();
        HttpURLConnection conn = null;
        InputStream is = null;
        BufferedReader br = null;
        try {
            URL url = new URL(m3uUrl);
            conn = (HttpURLConnection) url.openConnection();
            String ua = HttpHeaderSpUtil.getUA();
            String ref = HttpHeaderSpUtil.getReferer();
            String ck = HttpHeaderSpUtil.getCookie();
            conn.setRequestProperty("User-Agent", ua);
            if (!ref.isEmpty()) conn.setRequestProperty("Referer", ref);
            if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            int code = conn.getResponseCode();
            if (code != 200) {
                LogSpUtil.addParseLog("【M3U拉取失败】" + m3uUrl + " 响应码：" + code);
                return chList;
            }
            is = conn.getInputStream();
            br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            ChannelBean temp = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    int splitIndex = line.lastIndexOf(",");
                    if (splitIndex == -1) continue;
                    String name = line.substring(splitIndex + 1);
                    temp = new ChannelBean();
                    temp.name = name;
                    temp.backupUrls = new ArrayList<>();
                    //提取tvg-id
                    if (line.contains("tvg-id=\"")) {
                        int s = line.indexOf("tvg-id=\"") + 8;
                        int e = line.indexOf("\"", s);
                        temp.tvgId = line.substring(s, e);
                    }
                    //分辨率
                    if (line.contains("resolution=")) {
                        int s = line.indexOf("resolution=") + 11;
                        int e = line.indexOf(" ", s);
                        if (e == -1) e = splitIndex;
                        temp.resolution = line.substring(s, e);
                    }
                    //码率
                    if (line.contains("bit=")) {
                        int s = line.indexOf("bit=") + 4;
                        int e = line.indexOf(" ", s);
                        if (e == -1) e = splitIndex;
                        temp.bitrate = line.substring(s, e);
                    }
                } else if (!line.isEmpty() && !line.startsWith("#") && temp != null) {
                    temp.url = line;
                    chList.add(temp);
                    temp = null;
                }
            }
            LogSpUtil.addParseLog("【M3U解析成功】" + m3uUrl + " 共" + chList.size() + "个频道");
        } catch (Exception e) {
            String err = android.util.Log.getStackTraceString(e);
            LogSpUtil.addParseLog("【M3U解析异常】" + m3uUrl + "\n" + err);
        } finally {
            try {
                if (br != null) br.close();
                if (is != null) is.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignore) {}
        }
        return chList;
    }
}
