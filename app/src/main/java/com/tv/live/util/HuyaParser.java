package com.tv.live.util;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

import okhttp3.Response;

public class HuyaParser {
    private static final Handler mMainHandler = new Handler(Looper.getMainLooper());
    
    private static final ConcurrentHashMap<Integer, CacheItem> SOURCE_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_VALID_MS = 120 * 1000;

    private static final String API_LIVE_INFO = "https://www.huya.com/cache.php?m=Live&do=getLiveInfo&roomId=%d";

    public interface OnParseResultListener {
        void onSuccess(String hlsUrl, String flvUrl, boolean isTogetherWatch);
        void onFailed(String errorMsg);
    }

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

    public static void parse(int roomId, OnParseResultListener listener) {
        Log.d("HuyaParser", "开始解析房间：" + roomId);
        if (roomId <= 0) {
            mMainHandler.post(() -> listener.onFailed("房间号不合法"));
            return;
        }
        long now = System.currentTimeMillis();
        CacheItem cache = SOURCE_CACHE.get(roomId);
        if (cache != null && now < cache.expireTime) {
            Log.d("HuyaParser", "使用缓存：hls=" + cache.hls);
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        Log.d("HuyaParser", "缓存未命中，开始获取播放地址");
        fetchPlayUrl(roomId, listener);
    }

    private static void fetchPlayUrl(final int roomId, final OnParseResultListener listener) {
        Thread thread = new Thread(() -> {
            String hlsUrl = "";
            String flvUrl = "";

            try {
                Log.d("HuyaParser", "尝试从 LiveInfo API 获取播放地址");
                String result = fetchFromLiveInfoAPI(roomId);
                if (!TextUtils.isEmpty(result)) {
                    if (result.endsWith(".m3u8")) {
                        hlsUrl = result;
                    } else {
                        flvUrl = result;
                    }
                    Log.d("HuyaParser", "从 LiveInfo API 获取到地址：" + result);
                }

                // 🟢【新增核心】如果 API 失败，直接从完整网页 HTML 源码中提取播放地址
                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从 PC 网页 HTML 源码中提取播放地址");
                    String pcHtml = fetchHtml("https://www.huya.com/%d", roomId);
                    if (!TextUtils.isEmpty(pcHtml)) {
                        String[] urls = extractUrlsFromHtml(pcHtml);
                        if (!TextUtils.isEmpty(urls[0])) {
                            hlsUrl = urls[0];
                            Log.d("HuyaParser", "从 PC 网页源码提取到 hls：" + hlsUrl);
                        }
                        if (!TextUtils.isEmpty(urls[1])) {
                            flvUrl = urls[1];
                            Log.d("HuyaParser", "从 PC 网页源码提取到 flv：" + flvUrl);
                        }
                    }
                }

            } catch (Exception e) {
                Log.d("HuyaParser", "获取播放地址异常：" + e.getMessage());
                e.printStackTrace();
            }

            final String finalHlsUrl = hlsUrl;
            final String finalFlvUrl = flvUrl;
            if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                long expire = System.currentTimeMillis() + CACHE_VALID_MS;
                SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, true, expire));
                mMainHandler.post(() -> listener.onSuccess(finalHlsUrl, finalFlvUrl, true));
            } else {
                mMainHandler.post(() -> listener.onFailed("未获取到播放地址，可能主播未开播"));
            }
        });
        thread.start();
    }

    private static String fetchFromLiveInfoAPI(int roomId) {
        try {
            String url = String.format(Locale.ROOT, API_LIVE_INFO, roomId);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");
            headers.put("Referer", "https://www.huya.com/");
            headers.put("Origin", "https://www.huya.com");
            
            Response response = NetUtil.getInstance().syncGetWithHeaders(url, headers);
            if (!response.isSuccessful() || response.body() == null) {
                return "";
            }
            
            String jsonStr = response.body().string();

            if (jsonStr.contains("<!DOCTYPE")) {
                return "";
            }

            try {
                JSONObject json = new JSONObject(jsonStr);
                JSONObject data = json.optJSONObject("data");
                if (data == null) return "";

                JSONObject stream = data.optJSONObject("stream");
                if (stream != null) {
                    String hls = stream.optString("hls");
                    if (!TextUtils.isEmpty(hls)) return hls;
                }

                JSONObject gameLiveInfo = data.optJSONObject("gameLiveInfo");
                if (gameLiveInfo != null) {
                    JSONObject streamInfo = gameLiveInfo.optJSONObject("liveStreamInfo");
                    if (streamInfo != null) {
                        String sHlsUrl = streamInfo.optString("sHlsUrl");
                        String sHlsAntiCode = streamInfo.optString("sHlsAntiCode");
                        if (!TextUtils.isEmpty(sHlsUrl)) {
                            if (!TextUtils.isEmpty(sHlsAntiCode)) sHlsUrl += "?" + sHlsAntiCode;
                            return sHlsUrl;
                        }
                    }
                }

            } catch (Exception ignored) {}
            
        } catch (IOException e) {}
        return "";
    }

    private static String fetchHtml(String urlPattern, int roomId) {
        try {
            String url = String.format(urlPattern, roomId);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");
            headers.put("Referer", "https://www.huya.com/");
            
            Response response = NetUtil.getInstance().syncGetWithHeaders(url, headers);
            if (!response.isSuccessful() || response.body() == null) return "";
            return response.body().string();
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchHtml 异常：" + e.getMessage());
        }
        return "";
    }

    // 🟢【重点修复】更新了 3 种匹配虎牙网页源码中 "sHlsUrl" 和 "sHlsAntiCode" 的正则
    private static String[] extractUrlsFromHtml(String html) {
        String hlsUrl = "";
        String flvUrl = "";
        try {
            // 匹配 1：标准 JSON 字段匹配 (包含 sHlsAntiCode)
            Pattern pattern1 = Pattern.compile("\"sHlsUrl\"\\s*:\\s*\"([^\"]+)\"[^}]*\"sHlsAntiCode\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern1.matcher(html);
            if (matcher.find()) {
                hlsUrl = matcher.group(1) + "?" + matcher.group(2);
            }

            // 匹配 2：如果没找到 AntiCode，只找 sHlsUrl
            if (TextUtils.isEmpty(hlsUrl)) {
                Pattern pattern2 = Pattern.compile("\"sHlsUrl\"\\s*:\\s*\"([^\"]+)\"");
                matcher = pattern2.matcher(html);
                if (matcher.find()) {
                    hlsUrl = matcher.group(1);
                }
            }

            // 匹配 3：直接找 .m3u8 结尾的链接（最后的兜底）
            if (TextUtils.isEmpty(hlsUrl)) {
                Pattern pattern3 = Pattern.compile("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*");
                matcher = pattern3.matcher(html);
                if (matcher.find()) {
                    hlsUrl = matcher.group(0);
                }
            }

            // 匹配 FLV 地址
            Pattern flvPattern = Pattern.compile("\"sFlvUrl\"\\s*:\\s*\"([^\"]+)\"");
            matcher = flvPattern.matcher(html);
            if (matcher.find()) {
                flvUrl = matcher.group(1);
            }

        } catch (Exception e) {
            Log.d("HuyaParser", "extractUrlsFromHtml 异常：" + e.getMessage());
        }
        return new String[]{hlsUrl, flvUrl};
    }

    public static void clearCache() { SOURCE_CACHE.clear(); }
    public static void release() { SOURCE_CACHE.clear(); }
}
