package com.tv.live;
import com.tv.live.util.NetUtil;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Headers;
import okhttp3.Response;

public class HuyaParser {
    private static final String TAG = "HuyaParser";
    private static final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private static final String API_ROOM_INFO = "https://www.huya.com/cache.mini-global-%s.json";
    private static final String API_PLAY_URL = "https://api.huya.com/m_push/%s";
    private static final Map<String, CacheItem> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_VALID_MS = 110 * 1000;

    // 回调接口
    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);
        void onFailed(String errorMsg);
    }

    // 缓存实体
    private static class CacheItem {
        String hls;
        String flv;
        boolean isTogether;
        long expireTime;
        CacheItem(String h, String f, boolean t, long exp) {
            hls = h;
            flv = f;
            isTogether = t;
        }
    }

    /** 对外统一入口，传入字符串roomUid（Channel.getChannelId()） */
    public static void parse(String roomUid, OnParseResultListener listener) {
        if (TextUtils.isEmpty(roomUid)) {
            mMainHandler.post(() -> listener.onFailed("解析失败：房间ID为空"));
            return;
        }
        long now = System.currentTimeMillis();
        CacheItem cache = SOURCE_CACHE.get(roomUid);
        if (cache != null && now < cache.expireTime) {
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        mExecutor.execute(() -> getRoomInfo(roomUid, listener));
    }

    // 兼容数字ID（废弃重载）
    @Deprecated
    public static void parse(int roomId, OnParseResultListener listener) {
        parse(String.valueOf(roomId), listener);
    }

    // 请求房间基础信息
    private static void getRoomInfo(String roomUid, OnParseResultListener listener) {
        String url = String.format(API_ROOM_INFO, roomUid);
        Headers headers = Net.getInstance().createHuyaFixedHeaders();
        try (Response response = NetUtil.getInstance().syncGet(url)) {
            if (response.code() == 403) {
                postFailed(listener, "解析失败：虎牙房间接口403防盗链拦截");
                return;
            }
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "解析失败：房间接口异常，响应码=" + response.code());
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);
            boolean isTogetherWatch = json.optInt("isVideoRoom", 0) == 1;
            String streamName = json.optString("stream", "");
            String uid = json.optString("uid", "");
            if (TextUtils.isEmpty(streamName) || TextUtils.isEmpty(uid)) {
                postFailed(listener, "解析失败：房间未开播，暂无直播流");
                return;
            }
            long wsTime = System.currentTimeMillis() / 1000;
            String wsSecret = calcSecret(uid, streamName, wsTime);
            getPlaySource(roomUid, streamName, wsTime, wsSecret, isTogetherWatch, listener);
        } catch (IOException e) {
            postFailed(listener, "解析失败：网络连接异常 " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "解析失败：房间数据解析出错 " + e.getMessage());
        }
    }

    // 请求播放地址接口
    private static void getPlaySource(String roomUid, String streamName, long wsTime, String wsSecret,
                                      boolean isTogetherWatch, OnParseResultListener listener) {
        StringBuilder apiUrl = new StringBuilder(String.format(API_PLAY_URL, roomUid));
        apiUrl.append("?m=8&do=hd&uid=").append(streamName)
                .append("&wsSecret=").append(wsSecret)
                .append("&wsTime=").append(wsTime)
                .append("&fm=57&ver=2108191723&tx=").append(System.currentTimeMillis());
        if (isTogetherWatch) {
            apiUrl.append("&seqid=").append(System.currentTimeMillis());
        }
        Headers headers = NetUtil.getInstance().createHuyaFixedHeaders();
        try (Response response = NetUtil.getInstance().syncGet(apiUrl.toString())) {
            if (response.code() == 403) {
                postFailed(listener, "解析失败：播放接口403防盗链拦截");
                return;
            }
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "解析失败：获取播放地址失败，响应码=" + response.code());
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);
            org.json.JSONArray streamArray = json.optJSONArray("data");
            if (streamArray == null || streamArray.length() == 0) {
                postFailed(listener, "解析失败：未获取到任何直播线路");
                return;
            }
            String hlsUrl = "";
            String flvUrl = "";
            for (int i = 0; i < streamArray.length(); i++) {
                JSONObject item = streamArray.getJSONObject(i);
                String url = item.optString("url");
                if (TextUtils.isEmpty(url)) continue;
                if (url.contains(".m3u8")) {
                    hlsUrl = url;
                } else if (url.contains(".flv")) {
                    flvUrl = url;
                }
            }
            if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                postFailed(listener, "解析失败：接口返回线路全部无效");
                return;
            }
            long expire = System.currentTimeMillis() + CACHE_VALID_MS;
            SOURCE_CACHE.put(roomUid, new CacheItem(hlsUrl, flvUrl, isTogetherWatch, expire));
            postSuccess(listener, hlsUrl, flvUrl, isTogetherWatch);
        } catch (IOException e) {
            postFailed(listener, "解析失败：网络请求超时 " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "解析失败：播放流数据解析出错 " + e.getMessage());
        }
    }

    // MD5签名计算
    private static String calcSecret(String uid, String stream, long time) {
        String raw = uid + stream + time + "97b64242aa187a74";
        return md5(raw).toLowerCase();
    }

    // MD5加密工具
    private static String md5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                int val = b & 0xff;
                if (val < 16) sb.append("0");
                sb.append(Integer.toHexString(val));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    // 主线程回调成功
    private static void postSuccess(OnParseResultListener listener, String hls, String flv, boolean isTogether) {
        mMainHandler.post(() -> listener.onSuccess(hls, flv, isTogether));
    }

    // 主线程回调失败
    private static void postFailed(OnParseResultListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    // 清空缓存（下拉刷新、切换分类时调用）
    public static void clearCache() {
        SOURCE_CACHE.clear();
    }

    // 页面销毁释放线程资源
    public static void release() {
        mExecutor.shutdownNow();
        SOURCE_CACHE.clear();
    }
}
