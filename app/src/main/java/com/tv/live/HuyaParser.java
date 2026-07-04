package com.tv.live.util;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
/**
 * 虎牙直播间 + 一起看影视 源解析工具
 * 直连虎牙官方接口，无第三方中转域名，本地缓存减少重复请求，降低403拦截概率
 */
public class HuyaParser {
    private static final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private static final OkHttpClient mClient = new OkHttpClient();
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    // 官方接口地址（无第三方中转 diyp.zxyxndc.top）
    private static final String API_ROOM_INFO = "https://www.huya.com/cache.mini-global-%d.json";
    private static final String API_PLAY_URL = "https://api.huya.com/m_push/%d";
    // 缓存：房间号 -> [hls, flv, 过期时间戳ms]
    private static final Map<Integer, CacheItem> SOURCE_CACHE = new HashMap<>();
    // 缓存有效期 110秒，ws签名一般120s失效，预留缓冲
    private static final long CACHE_VALID_MS = 110 * 1000;

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
            expireTime = exp;
        }
    }

    /**
     * 入口：根据房间号解析播放源（优先读取缓存）
     */
    public static void parse(int roomId, OnParseResultListener listener) {
        if (roomId <= 0) {
            mMainHandler.post(() -> listener.onFailed("房间号不合法"));
            return;
        }
        // 校验缓存是否有效
        CacheItem cache = SOURCE_CACHE.get(roomId);
        long now = System.currentTimeMillis();
        if (cache != null && now < cache.expireTime) {
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        // 缓存失效/无缓存，走网络请求
        mExecutor.execute(() -> getRoomInfo(roomId, listener));
    }

    private static void getRoomInfo(int roomId, OnParseResultListener listener) {
        String url = String.format(API_ROOM_INFO, roomId);
        Map<String, String> headers = getBaseHeaders();
        Request request = new Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.of(headers))
                .get()
                .build();
        try (Response response = mClient.newCall(request).execute()) {
            if (response.code() == 403) {
                postFailed(listener, "HTTP 403 虎牙接口访问被拦截");
                return;
            }
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "请求房间信息失败，响应码：" + response.code());
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);
            boolean isTogetherWatch = json.optInt("isVideoRoom", 0) == 1;
            String streamName = json.optString("stream", "");
            String uid = json.optString("uid", "");
            if (TextUtils.isEmpty(streamName) || TextUtils.isEmpty(uid)) {
                postFailed(listener, "房间未开播或无流信息");
                return;
            }
            long wsTime = System.currentTimeMillis() / 1000;
            String wsSecret = calcSecret(uid, streamName, wsTime);
            getPlaySource(roomId, streamName, wsTime, wsSecret, isTogetherWatch, listener);
        } catch (IOException e) {
            postFailed(listener, "网络异常：" + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "解析数据异常：" + e.getMessage());
        }
    }

    private static void getPlaySource(int roomId, String streamName, long wsTime, String wsSecret,
                                      boolean isTogetherWatch, OnParseResultListener listener) {
        StringBuilder apiUrl = new StringBuilder(String.format(API_PLAY_URL, roomId));
        apiUrl.append("?m=8&do=hd&uid=").append(streamName)
                .append("&wsSecret=").append(wsSecret)
                .append("&wsTime=").append(wsTime)
                .append("&fm=57&ver=2108191723&tx=").append(System.currentTimeMillis());
        if (isTogetherWatch) {
            apiUrl.append("&seqid=").append(System.currentTimeMillis());
        }
        Map<String, String> headers = getBaseHeaders();
        Request request = new Request.Builder()
                .url(apiUrl.toString())
                .headers(okhttp3.Headers.of(headers))
                .get()
                .build();
        try (Response response = mClient.newCall(request).execute()) {
            if (response.code() == 403) {
                postFailed(listener, "HTTP 403 播放接口防盗链拦截");
                return;
            }
            if (!response.isSuccessful() || response.body() == null) {
                postFailed(listener, "获取播放源失败，响应码：" + response.code());
                return;
            }
            String resStr = response.body().string();
            JSONObject json = new JSONObject(resStr);
            JSONArray streamArray = json.optJSONArray("data");
            if (streamArray == null || streamArray.length() == 0) {
                postFailed(listener, "暂无可用播放流");
                return;
            }
            String hlsUrl = "";
            String flvUrl = "";
            for (int i = 0; i < streamArray.length(); i++) {
                JSONObject item = streamArray.getJSONObject(i);
                String url = item.optString("url", "");
                if (TextUtils.isEmpty(url)) continue;
                if (url.contains(".m3u8")) {
                    hlsUrl = url;
                } else if (url.contains(".flv")) {
                    flvUrl = url;
                }
            }
            // 写入缓存
            long expire = System.currentTimeMillis() + CACHE_VALID_MS;
            SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, isTogetherWatch, expire));
            postSuccess(listener, hlsUrl, flvUrl, isTogetherWatch);
        } catch (IOException e) {
            postFailed(listener, "网络异常：" + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "播放源解析异常：" + e.getMessage());
        }
    }

    /**
     * 计算 wsSecret MD5 签名
     */
    private static String calcSecret(String uid, String stream, long time) {
        String raw = uid + stream + time + "97b64242aa187a74";
        return md5(raw).toLowerCase();
    }

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

    /** 优化请求头，伪装PC浏览器，降低TV设备识别拦截概率 */
    private static Map<String, String> getBaseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.huya.com/");
        headers.put("Origin", "https://www.huya.com");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        return headers;
    }

    private static void postSuccess(OnParseResultListener listener, String hls, String flv, boolean isTogether) {
        mMainHandler.post(() -> listener.onSuccess(hls, flv, isTogether));
    }

    private static void postFailed(OnParseResultListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    /**
     * 清空缓存（切换订阅源/清空记录时调用）
     */
    public static void clearCache() {
        SOURCE_CACHE.clear();
    }

    /**
     * 释放线程池（Activity onDestroy 调用）
     */
    public static void release() {
        mExecutor.shutdownNow();
        SOURCE_CACHE.clear();
    }
}
