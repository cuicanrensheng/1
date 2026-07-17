package com.tv.live.loader;

import com.tv.live.Channel;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Response;

public class HuyaTogetherWatchFetcher {

    // 虎牙一起看列表 API
    private static final String API_BASE_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage";

    // 您指定的分类 ID（电影6个 + 电视剧6个 + 动画 + 综艺）
    private static final int[] TAG_IDS = {
        2067, 2069, 2071, 2073, 2075, 2077, // 电影
        2079, 2081, 2083, 2085, 2087, 2089, // 电视剧
        6861,                               // 动画
        1011                                // 综艺
    };

    /**
     * 拉取所有已配置分类的虎牙“一起看”房间列表
     * @param maxPagesPerTag 每个分类拉取几页（每页约120个房间）
     * @return 合并后的频道列表
     */
    public static List<Channel> fetchAll(int maxPagesPerTag) {
        List<Channel> result = new ArrayList<>();
        for (int tagId : TAG_IDS) {
            result.addAll(fetchByTagId(maxPagesPerTag, tagId));
        }
        android.util.Log.d("HuyaFetcher", "【虎牙】拉取完成，共获得 " + result.size() + " 个频道");
        return result;
    }

    private static List<Channel> fetchByTagId(int maxPages, int tagId) {
        List<Channel> result = new ArrayList<>();
        String tagName = getTagName(tagId);
        android.util.Log.d("HuyaFetcher", "开始拉取分类：" + tagName + " (tagId=" + tagId + ")");

        for (int page = 1; page <= maxPages; page++) {
            try {
                StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
                urlBuilder.append("&page=").append(page);
                urlBuilder.append("&gameId=3"); // 3 代表“一起看”分类
                urlBuilder.append("&tagId=").append(tagId);

                String url = urlBuilder.toString();
                Response response = NetUtil.getInstance().syncGet(url);
                if (!response.isSuccessful() || response.body() == null) {
                    android.util.Log.e("HuyaFetcher", tagName + " 第 " + page + " 页请求失败，code=" + response.code());
                    break;
                }

                String jsonStr = response.body().string();
                JSONObject json = new JSONObject(jsonStr);
                JSONObject data = json.optJSONObject("data");
                if (data == null) break;

                JSONArray datas = data.optJSONArray("datas");
                if (datas == null || datas.length() == 0) break;

                for (int i = 0; i < datas.length(); i++) {
                    JSONObject item = datas.getJSONObject(i);

                    String roomId = String.valueOf(item.optInt("roomId"));
                    String roomName = item.optString("roomName");

                    if (roomId.isEmpty() || roomName.isEmpty()) continue;

                    // 🟢 使用 huya:// 协议，分组为当前分类名称（如“电影(综合)”）
                    Channel channel = new Channel(roomName, "huya://" + roomId, tagName, roomId);
                    result.add(channel);
                }
            } catch (Exception e) {
                android.util.Log.e("HuyaFetcher", tagName + " 拉取异常：", e);
                break;
            }
        }
        android.util.Log.d("HuyaFetcher", tagName + " 拉取到 " + result.size() + " 个房间");
        return result;
    }

    private static String getTagName(int tagId) {
        switch (tagId) {
            case 2067: return "电影(综合)";
            case 2069: return "电影(喜剧)";
            case 2071: return "电影(动作)";
            case 2073: return "电影(惊悚)";
            case 2075: return "电影(科幻)";
            case 2077: return "电影(古装)";
            case 2079: return "电视剧(综合)";
            case 2081: return "电视剧(古装)";
            case 2083: return "电视剧(军旅)";
            case 2085: return "电视剧(搞笑)";
            case 2087: return "电视剧(悬疑)";
            case 2089: return "电视剧(都市)";
            case 6861: return "动画";
            case 1011: return "综艺";
            default: return "未知分类";
        }
    }
}
