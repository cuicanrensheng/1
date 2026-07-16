package com.tv.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 专门针对 { "live": { "分组": [ ... ] } } 格式的 JSON 直播源解析器
 */
public class JsonLiveParser {

    public static List<Channel> parse(String url) throws Exception {
        StringBuilder content = new StringBuilder();
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP 错误: " + responseCode);
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        } finally {
            if (reader != null) reader.close();
            if (conn != null) conn.disconnect();
        }

        return parseContent(content.toString());
    }

    public static List<Channel> parseContent(String content) throws Exception {
        List<Channel> channelList = new ArrayList<>();
        JSONObject root = new JSONObject(content);

        // 1. 找到最外层的 "live"
        if (root.has("live")) {
            JSONObject liveObj = root.getJSONObject("live");

            // 2. 遍历 live 下的每一个分组 Key（比如 "央视"）
            for (String groupName : liveObj.keySet()) {
                // 获取该分组下的频道数组
                JSONArray channelArray = liveObj.getJSONArray(groupName);

                // 3. 遍历数组中的每一个频道对象
                for (int i = 0; i < channelArray.length(); i++) {
                    JSONObject item = channelArray.getJSONObject(i);

                    String name = item.optString("name", "未知频道");
                    String contentId = item.optString("contentId", "");

                    // 4. 关键点：取出 urls 数组
                    JSONArray urlsArray = item.optJSONArray("urls");
                    if (urlsArray == null || urlsArray.length() == 0) {
                        continue;
                    }

                    // 5. 把数组里第一个有效的地址作为主播放地址
                    String firstUrl = "";
                    List<String> backupUrls = new ArrayList<>();

                    for (int j = 0; j < urlsArray.length(); j++) {
                        String url = urlsArray.getString(j).trim();
                        if (!url.isEmpty()) {
                            if (firstUrl.isEmpty()) {
                                firstUrl = url; // 第一个作为主源
                            } else {
                                backupUrls.add(url); // 后续加入备用源
                            }
                        }
                    }

                    if (firstUrl.isEmpty()) continue;

                    // 6. 创建频道对象
                    Channel channel = new Channel(name, firstUrl, groupName, contentId);
                    // 把备用源全部加进去
                    for (String backupUrl : backupUrls) {
                        channel.addBackupUrl(backupUrl);
                    }

                    channelList.add(channel);
                }
            }
        }

        return channelList;
    }
}
