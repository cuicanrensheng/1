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

    // 🟢 虎牙官方 API 地址（普通直播间有效，一起看房间无效）
    private static final String API_LIVE_INFO = "https://www.huya.com/cache.php?m=Live&do=getLiveInfo&roomId=%d";

    // ========== 一起看页面JS正则 ==========
    private static final Pattern PATTERN_VIDEO_STREAM = Pattern.compile("\"videoStream\"\\s*:\\s*\"([^\"]+)\"");

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

    /**
     * 【重要】替换此处为你项目内「网络调试日志弹窗」打印方法
     * 示例：LogUtil.appendLog(content);
     */
    private static void appendDebugLog(String content) {
        Log.i("HuyaDebugUI", content);
        // ========在这里改成你自己输出到界面日志的代码========
        // Example: LogHelper.getInstance().addLog(content);
        // ===================================================
    }

    public static void parse(int roomId, OnParseResultListener listener) {
        Log.d("HuyaParser", "开始解析房间：" + roomId);
        appendDebugLog("【虎牙解析】开始请求房间号：" + roomId);

        if (roomId <= 0) {
            appendDebugLog("【虎牙解析失败】房间号不合法");
            mMainHandler.post(() -> listener.onFailed("房间号不合法"));
            return;
        }
        long now = System.currentTimeMillis();
        CacheItem cache = SOURCE_CACHE.get(roomId);
        if (cache != null && now < cache.expireTime) {
            String tip = "【虎牙解析】使用缓存 | hls=" + cache.hls + " | 是否一起看：" + cache.isTogether;
            Log.d("HuyaParser", tip);
            appendDebugLog(tip);
            mMainHandler.post(() -> listener.onSuccess(cache.hls, cache.flv, cache.isTogether));
            return;
        }
        appendDebugLog("【虎牙解析】缓存失效，发起网络请求 roomId=" + roomId);
        Log.d("HuyaParser", "缓存未命中，开始获取播放地址");
        fetchPlayUrl(roomId, listener);
    }

    private static void fetchPlayUrl(final int roomId, final OnParseResultListener listener) {
        Thread thread = new Thread(() -> {
            String hlsUrl = "";
            String flvUrl = "";
            boolean isTogetherWatch = false;

            try {
                Log.d("HuyaParser", "尝试从 LiveInfo API 获取播放地址");
                appendDebugLog("【虎牙解析】第一步：调用LiveInfo接口");
                String result = fetchFromLiveInfoAPI(roomId);
                if (!TextUtils.isEmpty(result)) {
                    if (result.endsWith(".m3u8")) {
                        hlsUrl = result;
                    } else {
                        flvUrl = result;
                    }
                    String tip = "【虎牙解析】API获取源地址：" + result;
                    Log.d("HuyaParser", tip);
                    appendDebugLog(tip);
                }

                if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                    appendDebugLog("【虎牙解析】API无数据，切换网页抓取方案");
                    Log.d("HuyaParser", "尝试从 PC 网页源码中抓取播放地址（备用方案，支持一起看）");
                    String pcHtml = fetchHtml("https://www.huya.com/%d", roomId);
                    if (!TextUtils.isEmpty(pcHtml)) {
                        String[] urls = extractUrlsFromHtml(pcHtml);
                        hlsUrl = urls[0];
                        flvUrl = urls[1];

                        // 一起看解析分支
                        if (TextUtils.isEmpty(hlsUrl) && TextUtils.isEmpty(flvUrl)) {
                            Log.d("HuyaParser", "普通直播字段为空，尝试解析一起看videoStream");
                            Matcher videoMatcher = PATTERN_VIDEO_STREAM.matcher(pcHtml);
                            if (videoMatcher.find()) {
                                hlsUrl = videoMatcher.group(1);
                                isTogetherWatch = true;
                                String tip = "【虎牙解析✅识别一起看】解析videoStream成功：" + hlsUrl;
                                Log.d("HuyaParser", tip);
                                appendDebugLog(tip);
                            }else{
                                appendDebugLog("【虎牙解析】网页未找到videoStream，非有效一起看房间");
                            }
                        }
                    }else{
                        appendDebugLog("【虎牙解析】网页请求失败");
                    }
                }

            } catch (Exception e) {
                String errMsg = "【虎牙解析异常】" + e.getMessage();
                Log.d("HuyaParser", errMsg);
                appendDebugLog(errMsg);
                e.printStackTrace();
            }

            final String finalHlsUrl = hlsUrl;
            final String finalFlvUrl = flvUrl;
            final boolean finalIsTogether = isTogetherWatch;
            if (!TextUtils.isEmpty(hlsUrl) || !TextUtils.isEmpty(flvUrl)) {
                long expire = System.currentTimeMillis() + CACHE_VALID_MS;
                SOURCE_CACHE.put(roomId, new CacheItem(hlsUrl, flvUrl, finalIsTogether, expire));
                String info = String.format("【虎牙解析完成】hls=%s flv=%s | 是否一起看:%b",finalHlsUrl,finalFlvUrl,finalIsTogether);
                appendDebugLog(info);
                mMainHandler.post(() -> listener.onSuccess(finalHlsUrl, finalFlvUrl, finalIsTogether));
            } else {
                String err = "【虎牙解析失败】未获取到播放地址，可能主播未开播/非有效一起看房间";
                appendDebugLog(err);
                mMainHandler.post(() -> listener.onFailed("未获取到播放地址，可能主播未开播/非有效一起看房间"));
            }
        });
        thread.start();
    }

    /**
     * 🟢 请求虎牙 getLiveInfo API，不跟随重定向
     */
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

            Response response = NetUtil.getInstance().syncGetNoRedirectWithHeaders(url, headers);
            if (!response.isSuccessful() || response.body() == null) {
                Log.d("HuyaParser", "API 请求失败，状态码：" + response.code());
                appendDebugLog("【虎牙API】请求状态码：" + response.code());
                return "";
            }

            String jsonStr = response.body().string();
            Log.d("HuyaParser", "API 返回长度：" + jsonStr.length());

            if (jsonStr.contains("<!DOCTYPE")) {
                Log.d("HuyaParser", "API 返回了 HTML 网页，跳过解析");
                appendDebugLog("【虎牙API警告】接口返回HTML（大概率一起看房间触发重定向）");
                return "";
            }

            try {
                JSONObject json = new JSONObject(jsonStr);
                JSONObject data = json.optJSONObject("data");
                if (data == null) {
                    Log.d("HuyaParser", "API JSON 中找不到 data 字段");
                    return "";
                }

                // 1. data.stream
                JSONObject stream = data.optJSONObject("stream");
                if (stream != null) {
                    String hls = stream.optString("hls");
                    String flv = stream.optString("flv");
                    if (!TextUtils.isEmpty(hls)) return hls;
                    if (!TextUtils.isEmpty(flv)) return flv;
                }

                // 2. data.gameLiveInfo.liveStreamInfo
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

                // 3. data.liveData.tLiveInfo.tLiveStreamInfo.vMultiStreamInfo
                JSONObject liveData = data.optJSONObject("liveData");
                if (liveData != null) {
                    JSONObject tLiveInfo = liveData.optJSONObject("tLiveInfo");
                    if (tLiveInfo != null) {
                        JSONObject tLiveStreamInfo = tLiveInfo.optJSONObject("tLiveStreamInfo");
                        if (tLiveStreamInfo != null) {
                            JSONArray vMultiStreamInfo = tLiveStreamInfo.optJSONArray("vMultiStreamInfo");
                            if (vMultiStreamInfo != null && vMultiStreamInfo.length() > 0) {
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
                appendDebugLog("【网页请求失败】code=" + response.code());
                return "";
            }
            return response.body().string();
        } catch (IOException e) {
            Log.d("HuyaParser", "fetchHtml 异常：" + e.getMessage());
        }
        return "";
    }

    private static String extractUrlFromJsonString(String jsonStr) {
        try {
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
        appendDebugLog("【虎牙解析】缓存已清空");
    }

    public static void release() {
        SOURCE_CACHE.clear();
    }
}
