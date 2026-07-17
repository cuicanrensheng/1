package com.tv.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HuyaTogetherWatchProvider {

    // 虎牙分类 Tag ID
    public static final int TAG_ID_ALL = 5;
    public static final int TAG_ID_MOVIE = 42;
    public static final int TAG_ID_TV = 43;
    public static final int TAG_ID_CARTOON = 44;
    public static final int TAG_ID_VARIETY = 45;

    // 随机 User-Agent 池
    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15"
    };
    private static final Random RANDOM = new Random();

    public static List<Channel> fetchChannelsByTagId(int tagId, String groupName) {
        List<Channel> list = new ArrayList<>();
        String apiUrl = "https://www.huya.com/cache.php?m=LiveList&do=profile&tagId=" + tagId + "&page=1";

        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENTS[RANDOM.nextInt(USER_AGENTS.length)]);

            int code = conn.getResponseCode();
            if (code != 200) return list;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray items = root.optJSONArray("items");
            if (items == null) return list;

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String roomId = item.optString("roomId");
                String title = item.optString("introduction");
                String nick = item.optString("nick");

                if (roomId.isEmpty()) continue;
                if (title.isEmpty()) title = nick + " 的轮播";

                Channel channel = new Channel(title, "huya://" + roomId, groupName, "hy_" + tagId + "_" + roomId);
                list.add(channel);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
}
