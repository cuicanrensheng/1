package com.tv.live.loader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;


import com.tv.live.Channel;
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.CacheManager;
import com.tv.live.util.LogManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class LiveSourceLoader {
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    private final CacheManager cacheManager;

    public enum AccelerateType {
        JSDELIVR, GHPROXY, GITMIRROR, NONE
    }
    private AccelerateType accelerateType = AccelerateType.JSDELIVR;
    private boolean accelerateEnabled = true;

    public interface LoadCallback {
        void onSuccess(List<Channel> channels);
        void onError(String errorMsg);
    }

    private LiveSourceLoader(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cacheManager = CacheManager.getInstance(context);
    }

    public static LiveSourceLoader getInstance(Context context) {
        if (instance == null) {
            instance = new LiveSourceLoader(context.getApplicationContext());
        }
        return instance;
    }

    private boolean isParseLogEnabled() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        return sp.getBoolean("parse_log_enable", false);
    }

    public void setAccelerateEnabled(boolean enabled) {
        this.accelerateEnabled = enabled;
        if (isParseLogEnabled()) {
            // 🟢【修改】直接用 LogManager.log 记录
            LogManager.log("【直播源加速】" + (enabled ? "已启用" : "已禁用"));
        }
    }

    public void setAccelerateType(AccelerateType type) {
        this.accelerateType = type;
        if (isParseLogEnabled()) {
            LogManager.log("【直播源加速】加速源切换为：" + getAccelerateTypeName(type));
        }
    }

    private String getAccelerateTypeName(AccelerateType type) {
        switch (type) {
            case JSDELIVR: return "jsDelivr CDN";
            case GHPROXY: return "ghproxy";
            case GITMIRROR: return "gitmirror";
            case NONE: return "不加速（直连）";
            default: return "未知";
        }
    }

    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                String originalUrl = UrlConfig.LIVE_URL;
                String acceleratedUrl = getAcceleratedUrl(originalUrl);
                
                if (!originalUrl.equals(acceleratedUrl) && isParseLogEnabled()) {
                    LogManager.log("【直播源加速】检测到 GitHub 链接，已自动加速");
                    LogManager.log("【直播源加速】原地址：" + originalUrl);
                    LogManager.log("【直播源加速】加速地址：" + acceleratedUrl);
                }

                String rawContent = downloadRawContent(acceleratedUrl);
                if (rawContent != null && !rawContent.isEmpty()) {
                    cacheManager.saveFileCache("live_source", rawContent);
                    if (isParseLogEnabled()) {
                        LogManager.log("【直播源】缓存已保存，大小：" + rawContent.length() + " 字节");
                    }
                }

                List<Channel> channels = PlaylistParser.parse(acceleratedUrl);
                mainHandler.post(() -> callback.onSuccess(channels));
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public String getAcceleratedUrl(String originalUrl) {
        if (!accelerateEnabled || originalUrl == null || originalUrl.trim().isEmpty()) {
            return originalUrl;
        }
        if (!isGitHubUrl(originalUrl)) {
            return originalUrl;
        }
        switch (accelerateType) {
            case JSDELIVR: return convertToJsdelivr(originalUrl);
            case GHPROXY: return convertToGhproxy(originalUrl);
            case GITMIRROR: return convertToGitmirror(originalUrl);
            case NONE:
            default: return originalUrl;
        }
    }

    private boolean isGitHubUrl(String url) {
        if (url == null) return false;
        return url.contains("raw.githubusercontent.com")
                || url.contains("github.com/") && url.contains("/raw/")
                || url.contains("raw.github.com");
    }

    private String convertToJsdelivr(String githubUrl) {
        try {
            GitHubUrlInfo info = parseGitHubUrl(githubUrl);
            if (info == null) return githubUrl;
            StringBuilder sb = new StringBuilder();
            sb.append("https://cdn.jsdelivr.net/gh/");
            sb.append(info.user);
            sb.append("/");
            sb.append(info.repo);
            if (info.branch != null && !info.branch.isEmpty()) {
                sb.append("@");
                sb.append(info.branch);
            }
            sb.append("/");
            sb.append(info.path);
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }

    private String convertToGhproxy(String githubUrl) {
        try {
            return "https://ghproxy.com/" + githubUrl;
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }

    private String convertToGitmirror(String githubUrl) {
        try {
            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) {
            e.printStackTrace();
            return githubUrl;
        }
    }

    private static class GitHubUrlInfo {
        String user;
        String repo;
        String branch;
        String path;
    }

    private GitHubUrlInfo parseGitHubUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            GitHubUrlInfo info = new GitHubUrlInfo();
            String cleanUrl = url;
            if (cleanUrl.startsWith("https://")) {
                cleanUrl = cleanUrl.substring(8);
            } else if (cleanUrl.startsWith("http://")) {
                cleanUrl = cleanUrl.substring(7);
            }
            if (cleanUrl.startsWith("raw.githubusercontent.com/")) {
                String pathPart = cleanUrl.substring("raw.githubusercontent.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }
            if (cleanUrl.startsWith("github.com/") && cleanUrl.contains("/raw/")) {
                Pattern pattern = Pattern.compile("github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)");
                Matcher matcher = pattern.matcher(cleanUrl);
                if (matcher.find()) {
                    info.user = matcher.group(1);
                    info.repo = matcher.group(2);
                    info.branch = matcher.group(3);
                    info.path = matcher.group(4);
                    return info;
                }
            }
            if (cleanUrl.startsWith("raw.github.com/")) {
                String pathPart = cleanUrl.substring("raw.github.com/".length());
                String[] parts = pathPart.split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String downloadRawContent(String urlStr) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        String currentUrl = urlStr;
        final int MAX_REDIRECT = 5;
        int redirectCount = 0;
        try {
            while (redirectCount <= MAX_REDIRECT) {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LiveTV M3U Downloader");
                conn.connect();
                int responseCode = conn.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    redirectCount++;
                    String location = conn.getHeaderField("Location");
                    if (isParseLogEnabled()) {
                        LogManager.log("【直播源下载重定向】第" + redirectCount + "次跳转，原地址：" + currentUrl + " -> Location：" + location);
                    }
                    if (location == null || location.isEmpty()) {
                        if (isParseLogEnabled()) {
                            LogManager.log("【直播源下载】重定向Location为空，终止下载");
                        }
                        return null;
                    }
                    if (!location.startsWith("http")) {
                        URL baseUrl = new URL(currentUrl);
                        currentUrl = new URL(baseUrl, location).toString();
                    } else {
                        currentUrl = location;
                    }
                    conn.disconnect();
                    conn = null;
                    if (redirectCount >= MAX_REDIRECT) {
                        if (isParseLogEnabled()) {
                            LogManager.log("【直播源下载】重定向已达最大次数" + MAX_REDIRECT + "，下载失败");
                        }
                        return null;
                    }
                    continue;
                }
                if (responseCode != 200) {
                    if (isParseLogEnabled()) {
                        LogManager.log("【直播源下载】响应码非200：" + responseCode + " url=" + currentUrl);
                    }
                    return null;
                }
                InputStream is = conn.getInputStream();
                String encoding = conn.getContentEncoding();
                if ((encoding != null && encoding.equalsIgnoreCase("gzip")) || currentUrl.endsWith(".gz")) {
                    is = new GZIPInputStream(is);
                }
                reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            if (isParseLogEnabled()) {
                LogManager.log("【直播源下载】下载异常：" + e.getMessage());
            }
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
