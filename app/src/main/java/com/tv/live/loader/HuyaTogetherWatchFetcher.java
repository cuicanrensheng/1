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
                
                // 【已修改】字段名从 gameHost 改为 gameHostName，匹配虎牙API返回的JSON
                String gameHost = item.optString("gameHostName", ""); 
                if (!TOGETHER_GAME_HOST.equals(gameHost)) continue;

                String roomUid = item.optString("uid", "");
                String roomTitle = item.optString("roomName", "").trim();
                if (roomUid.isEmpty() || roomTitle.isEmpty() || roomIdSet.contains(roomUid)) continue;
                roomIdSet.add(roomUid);

                String groupName = getMediaGroup(roomTitle);
                Channel channel = new Channel(roomTitle, "", groupName, roomUid);
                pageChannels.add(channel);
            }
        } catch (Exception e) {
            Log.e("HuyaFetcher", "分页异常 page=" + page, e);
        }
        return pageChannels;
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
