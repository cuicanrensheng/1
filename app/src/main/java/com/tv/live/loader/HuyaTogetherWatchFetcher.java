package com.tv.live.loader;

import com.tv.live.Channel;
import com.tv.live.util.NetUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import okhttp3.Response;
import android.util.Log;

public class HuyaTogetherWatchFetcher {
    // 虎牙直播列表基础接口
    private static final String API_BASE_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage";
    // 一起看专区固定标识
    private static final String TOGETHER_GAME_HOST = "seeTogether";
    // 房间去重
    private final Set<String> roomIdSet = new HashSet<>();

    /**
     * 对外入口：拉取全部虎牙一起看，自动按影视类型分组
     * @param maxPages 最大分页
     * @return 全部分类频道列表
     */
    public List<Channel> fetchAllTogetherWatch(int maxPages) {
        List<Channel> result = new ArrayList<>();
        roomIdSet.clear();
        Log.d("HuyaFetcher", "开始拉取虎牙一起看影视，自动细分类型");

        for (int page = 1; page <= maxPages; page++) {
            List<Channel> pageChannels = fetchSinglePage(page);
            if (pageChannels.isEmpty()) {
                Log.d("HuyaFetcher", "第" + page + "页无数据，停止分页");
                break;
            }
            result.addAll(pageChannels);
        }

        Log.d("HuyaFetcher", "虎牙一起看拉取完成，总频道数：" + result.size());
        return result;
    }

    /**
     * 单页请求、过滤一起看、自动识别影视类型分组
     */
    private List<Channel> fetchSinglePage(int page) {
        List<Channel> pageResult = new ArrayList<>();
        try {
            StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
            urlBuilder.append("&page=").append(page);
            urlBuilder.append("&gameId=0"); // gameId=0 拉全分区才能抓到一起看
            String url = urlBuilder.toString();

            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.e("HuyaFetcher", "第" + page + "页请求失败");
                return pageResult;
            }

            String jsonStr = response.body().string();
            JSONObject rootJson = new JSONObject(jsonStr);
            JSONObject data = rootJson.optJSONObject("data");
            if (data == null) return pageResult;

            JSONArray datas = data.optJSONArray("datas");
            if (datas == null || datas.length() == 0) return pageResult;

            for (int i = 0; i < datas.length; i++) {
                JSONObject item = datas.getJSONObject(i);
                String gameHost = item.optString("gameHost", "");
                // 只保留一起看分区
                if (!TOGETHER_GAME_HOST.equals(gameHost)) {
                    continue;
                }

                String roomUid = item.optString("uid", "");
                String roomTitle = item.optString("roomName", "").trim();
                if (roomUid.isEmpty() || roomTitle.isEmpty() || roomIdSet.contains(roomUid)) {
                    continue;
                }
                roomIdSet.add(roomUid);

                // ========== 自动判断影视类型，生成分组group ==========
                String group = getMediaGroup(roomTitle);

                // 适配你现有Channel构造：name, mainPlayUrl, group, channelId
                Channel channel = new Channel(
                        roomTitle,
                        "", // 播放地址播放时再解析填充
                        group,
                        roomUid
                );
                pageResult.add(channel);
            }
        } catch (Exception e) {
            Log.e("HuyaFetcher", "分页异常 page=" + page, e);
        }
        return pageResult;
    }

    /**
     * 根据直播间标题自动区分影视分类
     */
    private String getMediaGroup(String title) {
        // 统一小写匹配，避免大小写干扰
        String lowTitle = title.toLowerCase();

        // 1. 电影类关键词
        if (lowTitle.contains("电影")
                || lowTitle.contains("院线")
                || lowTitle.contains("大片")
                || lowTitle.contains("影片")
                || lowTitle.contains("热映")) {
            return "虎牙一起看-电影";
        }

        // 2. 电视剧类关键词
        if (lowTitle.contains("电视剧")
                || lowTitle.contains("剧集")
                || lowTitle.contains("全集")
                || lowTitle.contains("连续剧")
                || lowTitle.contains("国产剧")
                || lowTitle.contains("韩剧")
                || lowTitle.contains("美剧")) {
            return "虎牙一起看-电视剧";
        }

        // 3. 动画/动漫类关键词
        if (lowTitle.contains("动漫")
                || lowTitle.contains("动画")
                || lowTitle.contains("二次元")
                || lowTitle.contains("番")
                || lowTitle.contains("国漫")
                || lowTitle.contains("日漫")) {
            return "虎牙一起看-动画";
        }

        // 4. 综艺类关键词
        if (lowTitle.contains("综艺")
                || lowTitle.contains("真人秀")
                || lowTitle.contains("晚会")
                || lowTitle.contains("脱口秀")
                || lowTitle.contains("选秀")
                || lowTitle.contains("娱乐节目")) {
            return "虎牙一起看-综艺";
        }

        // 不匹配以上的一起看房间
        return "虎牙一起看-其他";
    }
}
