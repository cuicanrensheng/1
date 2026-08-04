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

import okhttp3.Response;

/**
 * 虎牙直播加载器（修正 JSON 解析字段）
 */
public class HuyaLiveLoader {

    private static final String TAG = "HuyaLiveLoader";

    // 虎牙 EPG API
    private static final String HUYA_EPG_URL =
            "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList?iGid=%d&iTmpId=%d&iPageNo=%d&iPageSize=%d";

    // 一起看 分类 ID
    private static final int CATEGORY_YIQIKAN_GID = 2135;
    private static final int CATEGORY_YIQIKAN_TMP_ID = 2079;

    // 默认参数
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_PAGES = 5;
    private static final long CACHE_VALID_MS = 30 * 60 * 1000L; // 30分钟缓存

    private final Context context;
    private final Handler mainHandler;
    private final NetUtil netUtil;

    private static volatile List<Channel> sCachedChannels;
    private static volatile long sCachedTime;
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
     * 加载所有分类的虎牙直播频道（可扩展）
     */
    public void loadAllCategories(LoadCallback callback) {
        loadCategories(Arrays.asList(new int[][]{{CATEGORY_YIQIKAN_GID, CATEGORY_YIQIKAN_TMP_ID}}), callback);
    }

    /**
     * 加载指定分类的频道
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
     * 请求单个分类的频道列表（分页）
     */
    private List<Channel> fetchCategory(int gid, int tmpId, Set<String> seenRoomIds) {
        List<Channel> channels = new ArrayList<>();
        try {
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
     * 解析虎牙 EPG 响应（修正字段）
     */
    private List<Channel> parseEpgResponse(String jsonText, int gid, Set<String> seenRoomIds) {
        List<Channel> channels = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(jsonText);
            // ✅ 直接读取 vList 数组，不再检查 code
            JSONArray dataArr = root.optJSONArray("vList");
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
     * 解析单个频道（修正字段映射）
     */
    private Channel parseChannel(JSONObject item, int gid) {
        try {
            // ✅ 房间号：lChannel（从 JSON 中看到）
            long roomIdLong = item.optLong("lChannel", 0);
            if (roomIdLong <= 0) {
                // 备用：iChannel 或 lUid
                roomIdLong = item.optLong("iChannel", 0);
            }
            if (roomIdLong <= 0) {
                roomIdLong = item.optLong("lUid", 0);
            }
            if (roomIdLong <= 0) {
                return null;
            }

            String roomId = String.valueOf(roomIdLong);

            // ✅ 名称：sNick
            String name = item.optString("sNick", "").trim();
            if (TextUtils.isEmpty(name)) {
                name = item.optString("sRoomName", "").trim();
            }
            if (TextUtils.isEmpty(name)) {
                name = "虎牙直播-" + roomId;
            }

            // ✅ 分组：sGameFullName（为 "一起看"）
            String group = item.optString("sGameFullName", "").trim();
            if (TextUtils.isEmpty(group)) {
                group = "虎牙直播";
            }

            // 创建 Channel（cid = 虎牙房间号）
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
     * 同步加载「一起看」频道（供 AppCoreManager 调用）
     */
    public List<Channel> loadSync() {
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
