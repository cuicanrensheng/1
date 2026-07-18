package com.tv.live.loader;

import org.json.JSONArray;
import org.json.JSONObject;
import com.tv.live.Channel;
import com.tv.live.util.NetUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import okhttp3.Response;
import android.util.Log;

public class HuyaTogetherWatchFetcher {
    private static final String API_BASE_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage";
    private static final String TOGETHER_GAME_HOST = "seeTogether";
    private final Set<String> roomIdSet = new HashSet<>();

    public List<Channel> fetchAllTogetherWatch(int maxPages) {
        List<Channel> result = new ArrayList<>();
        roomIdSet.clear();
        Log.d("HuyaFetcher", "开始拉取虎牙一起看细分影视房间");
        for (int page = 1; page <= maxPages; page++) {
            List<Channel> pageData = fetchSinglePage(page);
            if (pageData.isEmpty()) break;
            result.addAll(pageData);
        }
        Log.d("HuyaFetcher", "拉取完成，总频道：" + result.size());
        return result;
    }

    private List<Channel> fetchSinglePage(int page) {
        List<Channel> pageChannels = new ArrayList<>();
        try {
            StringBuilder urlSb = new StringBuilder(API_BASE_URL);
            urlSb.append("&page=").append(page);
            urlSb.append("&gameId=0");
            String url = urlSb.toString();
            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) return pageChannels;
            String jsonStr = response.body().string();
            
            JSONObject root = new JSONObject(jsonStr); 
            JSONObject data = root.optJSONObject("data");
            if (data == null) return pageChannels;
            JSONArray datas = data.optJSONArray("datas");
            if (datas == null || datas.length() == 0) return pageChannels;

            for (int i = 0; i < datas.length(); i++) {
                JSONObject item = datas.getJSONObject(i);
                
                String gameHost = item.optString("gameHostName", ""); 
                if (!TOGETHER_GAME_HOST.equals(gameHost)) continue;

                String roomUid = item.optString("uid", "");
                String roomTitle = item.optString("roomName", "").trim();
                if (roomUid.isEmpty() || roomTitle.isEmpty() || roomIdSet.contains(roomUid)) continue;
                roomIdSet.add(roomUid);

                String groupName = getMediaGroup(roomTitle);

                // 【关键修改】不再传空字符串，而是先获取真实的流地址
                String realPlayUrl = resolveHuyaStreamUrl(roomUid);
                if (realPlayUrl == null || realPlayUrl.isEmpty()) {
                    Log.d("HuyaFetcher", "跳过无法获取流地址的房间: " + roomTitle);
                    continue; // 获取不到播放地址就不添加这个频道
                }

                Channel channel = new Channel(roomTitle, realPlayUrl, groupName, roomUid);
                pageChannels.add(channel);
            }
        } catch (Exception e) {
            Log.e("HuyaFetcher", "分页异常 page=" + page, e);
        }
        return pageChannels;
    }

    // ============================================================
    // 【新增方法】根据虎牙房间 uid 获取真实的 m3u8/flv 播放地址
    // 必须带 User-Agent 和 Referer，否则虎牙防盗链直接返回 404
    // ============================================================
    private String resolveHuyaStreamUrl(String uid) {
        try {
            String apiUrl = "https://www.huya.com/live/getLiveInfo?uid=" + uid;
            
            // 手动构造带请求头的 OkHttp 请求（解决虎牙防盗链 404 问题）
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Referer", "https://www.huya.com/")
                    .build();

            okhttp3.Response response = client.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) return null;

            String jsonStr = response.body().string();
            JSONObject root = new JSONObject(jsonStr);

            // 虎牙接口通常返回 status:200 表示成功
            if (root.optInt("status") != 200) return null;

            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            JSONObject stream = data.optJSONObject("stream");
            if (stream == null) return null;

            // 兼容两种字段名（虎牙偶尔会变）
            String flvUrl = stream.optString("flvUrl");
            if (flvUrl.isEmpty()) flvUrl = stream.optString("flv");

            String m3u8Url = stream.optString("m3u8Url");
            if (m3u8Url.isEmpty()) m3u8Url = stream.optString("m3u8");

            // 优先返回 flv，没有则返回 m3u8
            if (!flvUrl.isEmpty()) return flvUrl;
            if (!m3u8Url.isEmpty()) return m3u8Url;

            return null;
        } catch (Exception e) {
            Log.e("HuyaFetcher", "解析流地址失败 uid=" + uid, e);
            return null;
        }
    }

    private String getMediaGroup(String title) {
        String low = title.toLowerCase();
        if (low.contains("喜剧")) return "一起看电影 (喜剧)";
        if (low.contains("动作")) return "一起看电影 (动作)";
        if (low.contains("惊悚")) return "一起看电影 (惊悚)";
        if (low.contains("科幻")) return "一起看电影 (科幻)";
        if (low.contains("古装") && low.contains("电影")) return "一起看电影 (古装)";

        if (low.contains("古装") && low.contains("剧")) return "一起看电视剧 (古装)";
        if (low.contains("军旅")) return "一起看电视剧 (军旅)";
        if (low.contains("搞笑")) return "一起看电视剧 (搞笑)";
        if (low.contains("悬疑")) return "一起看电视剧 (悬疑)";
        if (low.contains("都市")) return "一起看电视剧 (都市)";

        if (low.contains("动漫") || low.contains("动画") || low.contains("番")) return "一起看动画";
        if (low.contains("综艺") || low.contains("真人秀")) return "一起看综艺";

        return "未知分类";
    }
}
