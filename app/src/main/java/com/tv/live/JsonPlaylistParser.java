package com.tv.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 万能 JSON 直播源解析器
 * 兼容：咪咕移动({"live":{}})、TVBox标准({"channels":[]})、纯数组等格式
 */
public class JsonPlaylistParser {

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
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        JSONObject root = new JSONObject(content);

        // 格式1：咪咕/移动格式 {"live": { "CCTV1": {...} }}
        if (root.has("live")) {
            JSONObject liveObj = root.getJSONObject("live");
            for (String key : liveObj.keySet()) {
                JSONObject item = liveObj.getJSONObject(key);
                String name = item.optString("name", key);
                String url = item.optString("url", "");
                String tvgId = item.optString("contentId", key);
                addToMap(channelMap, name, url, "未分类", tvgId);
            }
            return new ArrayList<>(channelMap.values());
        }

        // 格式2：TVBox 格式 {"channels": [ {...} ]}
        if (root.has("channels")) {
            JSONArray channels = root.getJSONArray("channels");
            for (int i = 0; i < channels.length(); i++) {
                JSONObject item = channels.getJSONObject(i);
                parseItemAndAdd(item, channelMap);
            }
            return new ArrayList<>(channelMap.values());
        }

        // 格式3：纯数组 [ {...}, {...} ]
        try {
            JSONArray rootArray = new JSONArray(content);
            for (int i = 0; i < rootArray.length(); i++) {
                JSONObject item = rootArray.getJSONObject(i);
                parseItemAndAdd(item, channelMap);
            }
        } catch (Exception ignored) {}

        return new ArrayList<>(channelMap.values());
    }

    private static void parseItemAndAdd(JSONObject item, Map<String, Channel> map) {
        String name = item.optString("name", item.optString("title", "未知频道"));
        String url = item.optString("url", item.optString("link", ""));
        String group = item.optString("group", item.optString("groupTitle", "未分类"));
        String tvgId = item.optString("tvgId", item.optString("id", ""));
        addToMap(map, name, url, group, tvgId);
    }

    private static void addToMap(Map<String, Channel> map, String name, String url, String group, String tvgId) {
        if (url.isEmpty()) return;
        String key = !tvgId.isEmpty() ? tvgId : name;
        Channel existing = map.get(key);
        if (existing != null) {
            existing.addBackupUrl(url);
            if (!group.isEmpty()) existing.setGroup(group);
        } else {
            map.put(key, new Channel(name, url, group, tvgId));
        }
    }
}
