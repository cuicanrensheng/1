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
    // 虎牙直播基础接口，不需要tag，拉全量直播后过滤「一起看seeTogether」
    private static final String API_BASE_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage";
    // seeTogether 标识：虎牙一起看专区gameHost固定值
    private static final String TOGETHER_GAME_HOST = "seeTogether";
    // 去重，避免重复房间
    private final Set<String> roomIdSet = new HashSet<>();

    /**
     * 对外入口：拉取所有虎牙一起看影视直播间（电影/剧集/动漫/综艺）
     * @param maxPages 最大拉取页数
     * @return 频道列表，直接给TV列表渲染
     */
    public List<Channel> fetchAllTogetherWatch(int maxPages) {
        List<Channel> result = new ArrayList<>();
        roomIdSet.clear();
        Log.d("HuyaFetcher", "开始拉取虎牙【一起看】全影视直播间");

        // 分页循环拉取
        for (int page = 1; page <= maxPages; page++) {
            List<Channel> pageChannels = fetchSinglePage(page);
            if (pageChannels.isEmpty()) {
                Log.d("HuyaFetcher", "第" + page + "页无数据，终止分页");
                break;
            }
            result.addAll(pageChannels);
        }

        Log.d("HuyaFetcher", "虎牙一起看拉取完成，有效影视房间总数：" + result.size());
        return result;
    }

    /**
     * 单页请求 + 过滤seeTogether直播间 + 封装Channel
     */
    private List<Channel> fetchSinglePage(int page) {
        List<Channel> pageResult = new ArrayList<>();
        try {
            // 构造请求参数：gameId传0拉全部直播，后续过滤host=seeTogether
            StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
            urlBuilder.append("&page=").append(page);
            urlBuilder.append("&gameId=0"); // 0=全部游戏/分区，才能抓到一起看
            String url = urlBuilder.toString();

            Response response = NetUtil.getInstance().syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.e("HuyaFetcher", "第" + page + "页接口请求失败");
                return pageResult;
            }

            String jsonStr = response.body().string();
            JSONObject json = new JSONObject(json);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return pageResult;

            JSONArray datas = data.optJSONArray("datas");
            if (datas == null || datas.length() == 0) return pageResult;

            // 遍历当前页所有直播间，只保留「一起看」
            for (int i = 0; i < datas.length; i++) {
                JSONObject item = datas.getJSONObject(i);
                String gameHost = item.optString("gameHostName", "");
                // 核心过滤：只保留 seeTogether 一起看分区
                if (!TOGETHER_GAME_HOST.equals(gameHost)) {
                    continue;
                }

                // 提取房间关键字段
                String roomId = String.valueOf(item.optString("uid", "")); // 直播间唯一ID
                String roomName = item.optString("roomName", ""); // 房间标题（影视名字）
                String nick = item.optString("nick", "虎牙影视主播"); // 主播名
                String coverImg = item.optString("screenshot", ""); // 封面图（TV列表展示）

                // 空值过滤、去重
                if (roomId.isEmpty() || roomName.isEmpty() || roomIdSet.contains(roomId)) {
                    continue;
                }
                roomIdSet.add(roomId);

                // 构建你的TV Channel实体，参数根据你Channel构造器调整
                // 示例构造：Channel(房间标题,封面,来源平台,房间ID,主播)
                Channel channel = new Channel(roomName, coverImg, "虎牙-一起看", roomId, nick);
                pageResult.add(channel);
            }

        } catch (Exception e) {
            Log.e("HuyaFetcher", "分页拉取异常 page=" + page, e);
        }
        return pageResult;
    }
}
