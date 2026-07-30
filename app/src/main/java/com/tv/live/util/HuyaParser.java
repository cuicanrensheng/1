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
    // 缓存时间 2 分钟，够用且不频繁请求虎牙
    private static final long CACHE_VALID_MS = 120 * 1000;

    // 🟢【核心修改】只保留虎牙目前最有效的两个接口
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
                // 🟢 首要尝试：访问虎牙官方 API (getLiveInfo)
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

                // 🟡 备选：如果 API 失败，尝试用模拟浏览器 UA 拉取网页（最后防线，防止接口变动）
                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    Log.d("HuyaParser", "尝试从 PC 网页源码中抓取播放地址（备用方案）");
                    String pcHtml = fetchHtml("https://www.huya.com/%d", roomId);
                    if (!TextUtils.isEmpty(pcHtml)) {
                        String[] result = extractUrlsFromHtml(pcHtml);
                        if (!TextUtils.isEmpty(result[0])) {
                            hlsUrl = result[0];
                            Log.d("HuyaParser", "从 PC 网页抓取到 hls：" + hlsUrl);
                        }
                        if (!TextUtils.isEmpty(result[1])) {
                            flvUrl = result[1];
                            Log.d("HuyaParser", "从 PC 网页抓取到 flv：" + flvUrl);
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

    /**
     * 🟢【核心修改】请求虎牙最新 getLiveInfo API
     */
    private static String fetchFromLiveInfoAPI(int roomId) {
        try {
            String url = String.format(Locale.ROOT, API_LIVE_INFO, roomId);
            
            // 模拟真实浏览器头部，防止 403 拦截
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.put("Connection", "keep-alive");
            headers.put("Referer", "https://www.huya.com/");
            headers.put("Origin", "https://www.huya.com");
            
            Response response = NetUtil.getInstance().syncGetWithHeaders(url, headers);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d("HuyaParser", "API 请求失败，状态码：" + response.code());
                return "";
            }
            
            String jsonStr = response.body().string();
            Log.d("HuyaParser", "API 返回长度：" + jsonStr.length());

            // 虎牙有时会抽风返回 HTML，非 JSON 直接跳过
            if (jsonStr.contains("<!DOCTYPE")) {
                return "";
            }

            try {
                JSONObject json = new JSONObject(jsonStr);
                // 虎牙 API 包裹在 data 字段下
                JSONObject data = json.optJSONObject("data");
                if (data == null) {
                    Log.d("HuyaParser", "API JSON 中找不到 data 字段");
                    return "";
                }

                // 1. 最可靠的数据层级：data.stream
                JSONObject stream = data.optJSONObject("stream");
                if (stream != null) {
                    String hls = stream.optString("hls");
                    String flv = stream.optString("flv");
                    if (!TextUtils.isEmpty(hls)) return hls;
                    if (!TextUtils.isEmpty(flv)) return flv;
                }

                // 2. 备用层级：data.gameLiveInfo.liveStreamInfo
                JSONObject gameLiveInfo = data.optJSONObject("gameLiveInfo");
                if (gameLiveInfo != null) {
                    JSONObject streamInfo = gameLiveInfo.optJSONObject("liveStreamInfo");
                    if (streamInfo != null) {
                        String sHlsUrl = streamInfo.optString("sHlsUrl");
                        String sHlsAntiCode = streamInfo.optString("sHlsAntiCode");
                        if (!TextUtils.isEmpty(sHlsUrl)) {
                            if (!TextUtils.isEmpty(sHlsAntiCode)) {
                                sHlsUrl += "?" + sHlsAntiCode;
                            }
                            return sHlsUrl;
                        }
                        
                        String sFlvUrl = streamInfo.optString("sFlvUrl");
                        String sFlvAntiCode = streamInfo.optString("sFlvAntiCode");
                        if (!TextUtils.isEmpty(sFlvUrl)) {
                            if (!TextUtils.isEmpty(sFlvAntiCode)) {
                                sFlvUrl += "?" + sFlvAntiCode;
                            }
                            return sFlvUrl;
                        }
                    }
                }

                // 3. 备用层级 2：data.liveData.tLiveInfo.tLiveStreamInfo.vMultiStreamInfo
                JSONObject liveData = data.optJSONObject("liveData");
                if (liveData != null) {
                    JSONObject tLiveInfo = liveData.optJSONObject("tLiveInfo");
                    if (tLiveInfo != null) {
                        JSONObject tLiveStreamInfo = tLiveInfo.optJSONObject("tLiveStreamInfo");
                        if (tLiveStreamInfo != null) {
                            JSONArray vMultiStreamInfo = tLiveStreamInfo.optJSONArray("vMultiStreamInfo");
                            if (vMultiStreamInfo != null && vMultiStreamInfo.length() > 0) {
                                // 取最高画质（通常第一个就是）
                                JSONObject streamItem = vMultiStreamInfo.getJSONObject(0);
                                if (streamItem != null) {
                                    String sHlsUrl = streamItem.optString("sHlsUrl");
                                    String sHlsAntiCode = streamItem.optString("sHlsAntiCode");
                                    if (!TextUtils.isEmpty(sHlsUrl)) {
                                        if (!TextUtils.isEmpty(sHlsAntiCode)) {
                                            sHlsUrl += "?" + sHlsAntiCode;
                                        }
                                        return sHlsUrl;
                                    }
                                    String sFlvUrl = streamItem.optString("sFlvUrl");
                                    String sFlvAntiCode = streamItem.optString("sFlvAntiCode");
                                    if (!TextUtils.isEmpty(sFlvUrl)) {
                                        if (!TextUtils.isEmpty(sFlvAntiCode)) {
                                            sFlvUrl += "?" + sFlvAntiCode;
                                        }
                                        return sFlvUrl;
                                    }
                                }
                            }
                        }
                    }
                }

            } catch (Exception e) {
                Log.d("HuyaParser", "API 返回非标准 JSON，尝试用正则提取");
                // 如果 JSON 解析失败（虎牙偶尔返回渣格式），强行用正则捞
                return extractUrlFromJsonString(jsonStr);
            }
            
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchFromLiveInfoAPI 异常：" + e.getMessage());
        }
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
            if (!response.isSuccessful() || response.body() == null) {
                return "";
            }
            return response.body().string();
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchHtml 异常：" + e.getMessage());
        }
        return "";
    }

    /**
     * 🔧 万能正则：从 JSON 或 HTML 中强行抠出 m3u8 和 flv 链接（带 antiCode 保护）
     */
    private static String extractUrlFromJsonString(String jsonStr) {
        try {
            // 提取 sHlsUrl 和 sHlsAntiCode
            Pattern hlsUrlPattern = Pattern.compile("\"sHlsUrl\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = hlsUrlPattern.matcher(jsonStr);
            if (matcher.find()) {
                String hlsUrl = matcher.group(1);
                Pattern antiPattern = Pattern.compile("\"sHlsAntiCode\"\\s*:\\s*\"([^\"]+)\"");
                Matcher antiMatcher = antiPattern.matcher(jsonStr);
                if (antiMatcher.find()) {
                    hlsUrl += "?" + antiMatcher.group(1);
                }
                return hlsUrl;
            }

            // 提取 sFlvUrl 和 sFlvAntiCode
            Pattern flvUrlPattern = Pattern.compile("\"sFlvUrl\"\\s*:\\s*\"([^\"]+)\"");
            matcher = flvUrlPattern.matcher(jsonStr);
            if (matcher.find()) {
                String flvUrl = matcher.group(1);
                Pattern antiPattern = Pattern.compile("\"sFlvAntiCode\"\\s*:\\s*\"([^\"]+)\"");
                Matcher antiMatcher = antiPattern.matcher(jsonStr);
                if (antiMatcher.find()) {
                    flvUrl += "?" + antiMatcher.group(1);
                }
                return flvUrl;
            }

            // 最后防线：找 .m3u8 或 .flv 结尾的链接
            Pattern httpM3u8 = Pattern.compile("https?://[^\"'\\s,]+\\.m3u8[^\"'\\s,]*");
            matcher = httpM3u8.matcher(jsonStr);
            if (matcher.find()) {
                return URLDecoder.decode(matcher.group(0), "UTF-8");
            }
            
            Pattern httpFlv = Pattern.compile("https?://[^\"'\\s,]+\\.flv[^\"'\\s,]*");
            matcher = httpFlv.matcher(jsonStr);
            if (matcher.find()) {
                return URLDecoder.decode(matcher.group(0), "UTF-8");
            }

        } catch (Exception e) {
            Log.d("HuyaParser", "extractUrlFromJsonString 异常：" + e.getMessage());
        }
        return "";
    }

    private static String[] extractUrlsFromHtml(String html) {
        String hlsUrl = "";
        String flvUrl = "";
        try {
            // 虎牙网页源码里经常会出现这两种模式
            Pattern sHlsUrlPattern = Pattern.compile("\"sHlsUrl\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = sHlsUrlPattern.matcher(html);
            if (matcher.find()) {
                hlsUrl = matcher.group(1);
                Pattern sHlsAntiPattern = Pattern.compile("\"sHlsAntiCode\"\\s*:\\s*\"([^\"]+)\"");
                Matcher antiMatcher = sHlsAntiPattern.matcher(html);
                if (antiMatcher.find()) {
                    hlsUrl = hlsUrl + "?" + antiMatcher.group(1);
                }
            }

            Pattern sFlvUrlPattern = Pattern.compile("\"sFlvUrl\"\\s*:\\s*\"([^\"]+)\"");
            matcher = sFlvUrlPattern.matcher(html);
            if (matcher.find()) {
                flvUrl = matcher.group(1);
                Pattern sFlvAntiPattern = Pattern.compile("\"sFlvAntiCode\"\\s*:\\s*\"([^\"]+)\"");
                Matcher antiMatcher = sFlvAntiPattern.matcher(html);
                if (antiMatcher.find()) {
                    flvUrl = flvUrl + "?" + antiMatcher.group(1);
                }
            }

        } catch (Exception e) {
            Log.d("HuyaParser", "extractUrlsFromHtml 异常：" + e.getMessage());
        }
        return new String[]{hlsUrl, flvUrl};
    }

    public static void clearCache() {
        SOURCE_CACHE.clear();
    }

    public static void release() {
        SOURCE_CACHE.clear();
    }
}
