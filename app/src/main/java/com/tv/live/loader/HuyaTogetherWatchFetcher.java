package com.tv.live.loader;

import android.util.Log;
import com.tv.live.Channel;
import com.tv.live.util.NetUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;

public class HuyaTogetherWatchFetcher {
    private static final String TAG = "HuyaTogetherWatchFetcher";
    private static final String API_BASE_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage";

    // 电影/电视剧/动画/综艺 tagId 合集
    private static final int[] TAG_IDS = {
            2067, 2069, 2071, 2073, 2075, 2077, // 电影
            2079, 2081, 2083, 2085, 2087, 2089, // 电视剧
            6861,                                 // 动画
            1011                                  // 综艺
    };

    /**
     * 拉取全部分类影视房间列表（仅基础信息，无播放地址）
     * @param maxPagesPerTag 每个分类最大拉取页数
     * @return 未填充播放url的Channel列表，roomId已存储
     */
    public static List<Channel> fetchAll(int maxPagesPerTag) {
        List<Channel> result = new ArrayList<>();
        for (int tagId : TAG_IDS) {
            List<Channel> tagChannels = fetchByTagId(maxPagesPerTag, tagId);
            result.addAll(tagChannels);
            Log.d(TAG, "分类[" + getTagName(tagId) + "]获取房间数：" + tagChannels.size());
        }
        Log.d(TAG, "【虎牙一起看】全部分类拉取完成，总房间数：" + result.size());
        return result;
    }

    /** 单分类分页拉取房间 */
    private static List<Channel> fetchByTagId(int maxPages, int tagId) {
        List<Channel> result = new ArrayList<>();
        String tagName = getTagName(tagId);
        Log.d(TAG, "开始拉取分类：" + tagName + " tagId=" + tagId);

        for (int page = 1; page <= maxPages; page++) {
            Response response = null;
            try {
                StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
                urlBuilder.append("&page=").append(page);
                urlBuilder.append("&gameId=3");
                urlBuilder.append("&tagId=").append(tagId);
                String url = urlBuilder.toString();

                response = NetUtil.getInstance().syncGet(url);
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "分类" + tagName + "第" + page + "页请求失败，终止分页");
                    break;
                }

                String jsonStr = response.body().string();
                JSONObject json = new JSONObject(jsonStr);
                JSONObject data = json.optJSONObject("data");
                if (data == null) break;

                JSONArray datas = data.optJSONArray("datas");
                if (datas == null || datas.length() == 0) {
                    Log.d(TAG, "分类" + tagName + "第" + page + "页无数据，结束");
                    break;
                }

                for (int i = 0; i < datas.length(); i++) {
                    JSONObject item = datas.getJSONObject(i);
                    String roomId = String.valueOf(item.optInt("roomId", 0));
                    String roomName = item.optString("roomName", "");
                    String coverImg = item.optString("screenshot", "");

                    if (roomId.isEmpty() || roomName.isEmpty() || "0".equals(roomId)) {
                        continue;
                    }
                    // Channel构造参数：name, logo, group, roomId
                    Channel channel = new Channel(roomName, coverImg, "虎牙影视-" + tagName, roomId);
                    result.add(channel);
                }
            } catch (Exception e) {
                Log.e(TAG, "拉取分类" + tagName + "第" + page + "页异常", e);
                break;
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        return result;
    }

    /** tagId -> 中文分类名 */
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
