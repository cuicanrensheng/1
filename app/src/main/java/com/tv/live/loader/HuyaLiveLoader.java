package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.Channel;
import com.tv.live.util.NetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Response;

/**
 * 虎牙直播加载器（从爱其意 app 移植）
 *
 * 功能:
 * 1. 通过虎牙官方 EPG API (live.cdn.huya.com/liveHttpUI/getTmpLiveList)
 *    获取「一起看」分类下的所有直播频道
 * 2. 返回 Channel 列表（cid 使用虎牙房间号）
 * 3. 实际播放由已有的 HuyaParser 通过房间号解析
 *
 * 数据来源: 爱其意 app logcat 抓包分析
 *   - HuyaEpg 请求URL=https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=2135&iTmpId=2079&iPageNo=1&iPageSize=24
 *   - 频道示例: 【丧尸来啦】搞笑奇葩僵尸 (roomId=1394575544)
 *   - 分类: 一起看 (iGid=2135, iTmpId=2079)
 *
 * 使用方法:
 *   HuyaLiveLoader loader = new HuyaLiveLoader(context);
 *   loader.loadTogetherWatchChannels(new HuyaLiveLoader.LoadCallback() {
 *       @Override
 *       public void onSuccess(List<Channel> channels) {
 *           // 处理频道列表
 *       }
 *       @Override
 *       public void onError(String error) {
 *           // 处理错误
 *       }
 *   });
 */
public class HuyaLiveLoader {

    private static final String TAG = "HuyaLiveLoader";

    // 虎牙 EPG API（来自爱其意 app logcat）
    private static final String HUYA_EPG_URL =
            "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=%d&iTmpId=%d&iPageNo=%d&iPageSize=%d";

    // 一起看 分类 ID（来自爱其意 logcat: iGid=2135, iTmpId=2079）
    private static final int CATEGORY_YIQIKAN_GID = 2135;
    private static final int CATEGORY_YIQIKAN_TMP_ID = 2079;

    // 其他虎牙分类（可扩展）
    private static final int[][] HUYA_CATEGORIES = {
            {2135, 2079},  // 一起看
            // 可添加更多分类:
            // {1, 1},      // 英雄联盟
            // {2, 2},      // 王者荣耀
            // ...
    };

    // 默认参数
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_PAGES = 5;
    private static final long CACHE_VALID_MS = 30 * 60 * 1000L; // 30分钟缓存

    private final Context context;
    private final Handler mainHandler;
    private final NetUtil netUtil;

    // 频道列表缓存
    private static volatile List<Channel> sCachedChannels;
    private static volatile long sCachedTime;
    // 加载中标志，防止并发加载
    private static final Object sLoadLock = new Object();

    public interface LoadCallback {
        void onSuccess(List<Channel> channels);
        void onError(String error);
    }

    public HuyaLiveLoader(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.netUtil = NetUtil.getInstance();
    }

    /**
     * 加载「一起看」分类下的所有直播频道
     */
    public void loadTogetherWatchChannels(LoadCallback callback) {
        loadCategories(Arrays.asList(new int[]{CATEGORY_YIQIKAN_GID, CATEGORY_YIQIKAN_TMP_ID}), callback);
    }

    /**
     * 加载所有分类的虎牙直播频道
     */
    public void loadAllCategories(LoadCallback callback) {
        loadCategories(Arrays.asList(HUYA_CATEGORIES), callback);
    }

    /**
     * 加载指定分类的频道
     *
     * @param categories 二维数组，每个元素是 {gid, tmpId}
     */
    private void loadCategories(final List<int[]> categories, final LoadCallback callback) {
        // 检查缓存
        if (sCachedChannels != null && (System.currentTimeMillis() - sCachedTime) < CACHE_VALID_MS) {
            Log.i(TAG, "使用缓存频道列表: " + sCachedChannels.size() + " 条");
            final List<Channel> cached = new ArrayList<>(sCachedChannels);
            mainHandler.post(() -> callback.onSuccess(cached));
            return;
        }

        new Thread(() -> {
            synchronized (sLoadLock) {
                try {
                    List<Channel> allChannels = new ArrayList<>();
                    Set<String> seenRoomIds = new HashSet<>();

                    for (int[] category : categories) {
                        int gid = category[0];
                        int tmpId = category[1];
                        List<Channel> categoryChannels = fetchCategory(gid, tmpId, seenRoomIds);
                        if (categoryChannels != null && !categoryChannels.isEmpty()) {
                            allChannels.addAll(categoryChannels);
                        }
                    }

                    if (!allChannels.isEmpty()) {
                        sCachedChannels = allChannels;
                        sCachedTime = System.currentTimeMillis();
                        final List<Channel> result = new ArrayList<>(allChannels);
                        mainHandler.post(() -> callback.onSuccess(result));
                    } else {
                        mainHandler.post(() -> callback.onError("未获取到任何频道"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "加载虎牙频道失败", e);
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            }
        }).start();
    }

    /**
     * 请求单个分类的频道列表
     */
    private List<Channel> fetchCategory(int gid, int tmpId, Set<String> seenRoomIds) {
        List<Channel> channels = new ArrayList<>();
        try {
            // 分页获取
            for (int page = 1; page <= DEFAULT_MAX_PAGES; page++) {
                String url = String.format(java.util.Locale.ROOT,
                        HUYA_EPG_URL, gid, tmpId, page, DEFAULT_PAGE_SIZE);
                Log.i(TAG, "请求 EPG: " + url);

                String jsonText = httpGetText(url);
                if (TextUtils.isEmpty(jsonText)) {
                    Log.w(TAG, "EPG 响应为空, page=" + page);
                    break;
                }

                List<Channel> pageChannels = parseEpgResponse(jsonText, gid, seenRoomIds);
                if (pageChannels == null || pageChannels.isEmpty()) {
                    Log.i(TAG, "EPG page=" + page + " 无更多频道");
                    break;
                }

                channels.addAll(pageChannels);

                // 如果本页少于 pageSize，说明已经是最后一页
                if (pageChannels.size() < DEFAULT_PAGE_SIZE) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchCategory 失败: gid=" + gid, e);
        }
        return channels;
    }

    /**
     * 解析虎牙 EPG 响应
     */
    private List<Channel> parseEpgResponse(String jsonText, int gid, Set<String> seenRoomIds) {
        List<Channel> channels = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(jsonText);
            // 虎牙 EPG 响应结构: { "code": 0, "message": "ok", "data": [...] }
            int code = root.optInt("code", -1);
            if (code != 0) {
                Log.w(TAG, "EPG 返回非成功 code: " + code + ", msg=" + root.optString("message"));
                return channels;
            }

            JSONArray dataArr = root.optJSONArray("data");
            if (dataArr == null || dataArr.length() == 0) {
                return channels;
            }

            for (int i = 0; i < dataArr.length(); i++) {
                JSONObject item = dataArr.optJSONObject(i);
                if (item == null) continue;

                Channel ch = parseChannel(item, gid);
                if (ch != null) {
                    String roomId = ch.getChannelId();
                    if (!seenRoomIds.contains(roomId)) {
                        seenRoomIds.add(roomId);
                        channels.add(ch);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 EPG 响应失败", e);
        }
        return channels;
    }

    /**
     * 解析单个频道
     */
    private Channel parseChannel(JSONObject item, int gid) {
        try {
            // 虎牙 EPG 数据结构（来自爱其意 logcat 中的 auk 字段）:
            // lSubChannelId: 房间号
            // sNick: 主播昵称
            // sIntroduction: 频道介绍
            // sGameName: 分类名（一起看、英雄联盟等）
            // sAvatarUrl: 头像

            long lSubChannelId = item.optLong("lSubChannelId", 0);
            if (lSubChannelId <= 0) {
                // 备用字段
                lSubChannelId = item.optLong("iRoomId", 0);
            }
            if (lSubChannelId <= 0) {
                lSubChannelId = item.optLong("roomId", 0);
            }

            if (lSubChannelId <= 0) {
                Log.d(TAG, "频道无房间号: " + item.toString().substring(0, Math.min(100, item.toString().length())));
                return null;
            }

            String roomId = String.valueOf(lSubChannelId);

            String name = item.optString("sNick", "").trim();
            if (TextUtils.isEmpty(name)) {
                name = item.optString("sRoomName", "").trim();
            }
            if (TextUtils.isEmpty(name)) {
                name = item.optString("sIntroduce", "").trim();
            }
            if (TextUtils.isEmpty(name)) {
                name = "虎牙直播-" + roomId;
            }

            String group = item.optString("sGameName", "").trim();
            if (TextUtils.isEmpty(group)) {
                group = "虎牙直播";
            }
            if (gid == CATEGORY_YIQIKAN_GID) {
                group = "一起看";
            }

            // 创建 Channel（cid = 虎牙房间号）
            // mainPlayUrl 使用 https://www.huya.com/{roomId} 格式
            // 这样 TVPlayerManager.isHuyaRoomUrl() 可以自动识别并调用 HuyaParser 解析
            String cid = "huya_" + roomId;
            Channel ch = new Channel(name, "https://www.huya.com/" + roomId, group, cid);

            Log.d(TAG, "解析频道: " + name + " (roomId=" + roomId + ", group=" + group + ")");
            return ch;
        } catch (Exception e) {
            Log.e(TAG, "parseChannel 失败", e);
        }
        return null;
    }

    /**
     * HTTP GET 请求并返回文本
     */
    private String httpGetText(String url) {
        try {
            // 使用 NetUtil（已配置好 UA / Referer 等）
            Response response = netUtil.syncGet(url);
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "请求失败: " + response.code());
                return null;
            }
            return response.body().string();
        } catch (IOException e) {
            Log.e(TAG, "httpGetText 失败: " + url, e);
            return null;
        }
    }

    /**
     * 同步加载「一起看」频道（供 AiyiPlayChainManager 调用）
     */
    public List<Channel> loadSync() {
        // 检查缓存
        if (sCachedChannels != null && (System.currentTimeMillis() - sCachedTime) < CACHE_VALID_MS) {
            return new ArrayList<>(sCachedChannels);
        }

        synchronized (sLoadLock) {
            try {
                List<Channel> allChannels = new ArrayList<>();
                Set<String> seenRoomIds = new HashSet<>();
                List<int[]> categories = new ArrayList<>();
                categories.add(new int[]{CATEGORY_YIQIKAN_GID, CATEGORY_YIQIKAN_TMP_ID});

                for (int[] category : categories) {
                    List<Channel> categoryChannels = fetchCategory(category[0], category[1], seenRoomIds);
                    if (categoryChannels != null && !categoryChannels.isEmpty()) {
                        allChannels.addAll(categoryChannels);
                    }
                }

                if (!allChannels.isEmpty()) {
                    sCachedChannels = allChannels;
                    sCachedTime = System.currentTimeMillis();
                }
                return allChannels;
            } catch (Exception e) {
                Log.e(TAG, "loadSync 失败", e);
                return new ArrayList<>();
            }
        }
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        synchronized (sLoadLock) {
            sCachedChannels = null;
            sCachedTime = 0;
            Log.i(TAG, "缓存已清除");
        }
    }
}
