package com.tv.live.util;
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
import okhttp3.Headers;
import okhttp3.Response;
/**
 * 虎牙直播间 + 一起看影视 源解析工具
 * 直连虎牙官方接口，无第三方中转域名
 * 网络统一使用全局NetUtil，解析/播放器UA、请求头完全统一，降低403防盗链拦截
 */
public class HuyaParser {
    private static final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    // 虎牙官方原生接口，移除第三方中转 diyp.zxyxndc.top
    private static final String API_ROOM_INFO = "https://www.huya.com/cache.mini-global-%d.json";
    private static final String API_PLAY_URL = "https://api.huya.com/m_push/%d";
    // 房间号缓存：key=房间号 value=流信息+过期时间
    private static final Map<Integer, CacheItem> SOURCE_CACHE = new HashMap<>();
    // ws签名有效期120秒，缓存设110秒提前失效，避免拉流403
    private static final long CACHE_VALID_MS = 110 * 100;

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
     * 对外入口，优先读取缓存，缓存失效再请求网络
     */
    public static void parse(int roomId, OnParseResultListener listener) {
        if (roomId <= 0) {
            mMainHandler.post(() -> listener.onFailed("房间号不合法"));
            return;
        }
        long now = System.currentTimeMillis();
        CacheItem cache = SOURCE_CACHE.get(roomId);
        // 缓存有效直接返回，不走网络
        if (cache != null && now < cache.expireTime) {
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        // 缓存过期，异步请求接口
        mExecutor.execute(() -> getRoomInfo(roomId, listener));
    }

    /**
     * 请求房间基础信息接口
     */
    private static void getRoomInfo(int roomId, OnParseResultListener listener) {
        String url = String.format(API_ROOM_INFO, roomId);
        // 调用NetUtil获取虎牙固定请求头
        Headers headers = NetUtil.getInstance().createHuyaFixedHeaders();
        try (Response response = NetUtil.getInstance().syncGet(url)) {
            if (response.code() == 403) {
                postFailed(listener, "HTTP 403 虎牙房间接口访问被拦截");
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
            // 生成时效ws签名
            long wsTime = System.currentTimeMillis() / 1000;
            String wsSecret = calcSecret(uid, streamName, wsTime);
            getPlaySource(roomId, streamName, wsTime, wsSecret, isTogetherWatch, listener);
        } catch (IOException e) {
            postFailed(listener, "网络请求异常：" + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "解析房间数据异常：" + e.getMessage());
        }
    }

    /**
     * 请求播放流地址接口
     */
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
        Headers headers = NetUtil.getInstance().createHuyaFixedHeaders();
        try (Response response = NetUtil.getInstance().syncGet(apiUrl.toString())) {
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
            // 修复JSONArray.length → length()
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
            // 写入内存缓存
            long expire = System.currentTimeMillis() + CACHE_VALID_MS;
            SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, isTogetherWatch, expire));
            postSuccess(listener, hlsUrl, flvUrl, isTogetherWatch);
        } catch (IOException e) {
            postFailed(listener, "网络请求异常：" + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            postFailed(listener, "解析播放流异常：" + e.getMessage());
        }
    }

    /**
     * 虎牙wsSecret MD5签名算法（本地计算，不依赖第三方）
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

    // 切主线程返回成功结果
    private static void postSuccess(OnParseResultListener listener, String hls, String flv, boolean isTogether) {
        mMainHandler.post(() -> listener.onSuccess(hls, flv, isTogether));
    }

    // 切主线程返回失败信息
    private static void postFailed(OnParseResultListener listener, String msg) {
        mMainHandler.post(() -> listener.onFailed(msg));
    }

    /**
     * 手动清空全部房间缓存（切换订阅源、清空记录时调用）
     */
    public static void clearCache() {
        SOURCE_CACHE.clear();
    }

    /**
     * Activity销毁释放线程池，防止内存泄漏
     */
    public static void release() {
        mExecutor.shutdownNow();
        SOURCE_CACHE.clear();
    }
}
