package com.tv.live.loader;

import com.tv.live.Channel;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Response;

/**
 * 虎牙“一起看”房间列表抓取器
 * 自动拉取电影、电视剧、动漫、综艺类直播间
 */
public class HuyaTogetherWatchFetcher {

    // 虎牙分类 API (返回 JSON)
    // page=1&gameId=3 是“一起看”分类的 ID (3 对应“一起看”)
    private static final String API_URL_TEMPLATE = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&page=%d&gameId=3";

    /**
     * 从虎牙拉取“一起看”房间列表
     * @param maxPages 最多拉取几页 (每页约 120 个房间)
     * @return 转换后的 Channel 列表
     */
    public static List<Channel> fetch(int maxPages) {
        List<Channel> result = new ArrayList<>();
        
        for (int page = 1; page <= maxPages; page++) {
            try {
                String url = String.format(API_URL_TEMPLATE, page);
                // 使用你项目中现成的 NetUtil 进行请求（复用请求头，避免防盗链）
                Response response = NetUtil.getInstance().syncGet(url);
                if (!response.isSuccessful() || response.body() == null) {
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
                    
                    // 提取必要字段
                    String roomId = String.valueOf(item.optInt("roomId"));
                    String roomName = item.optString("roomName");
                    String gameName = item.optString("gameName"); // 如 "一起看"

                    // 过滤掉不是“一起看”的（防止 API 混入其他分类）
                    if (!"一起看".equals(gameName)) continue;

                    // 跳过没有房间号或名称的脏数据
                    if (roomId.isEmpty() || roomName.isEmpty()) continue;

                    // 构建 Channel 对象
                    // 关键：将 roomId 存入 channelId 字段，供 HuyaParser 使用
                    Channel channel = new Channel(roomName, "", "虎牙一起看", roomId);
                    
                    // 为防止重复，可选去重逻辑 (按 roomId 去重)
                    result.add(channel);
                }
            } catch (Exception e) {
                e.printStackTrace();
                break; // 某一页报错，提前结束
            }
        }
        return result;
    }
}
