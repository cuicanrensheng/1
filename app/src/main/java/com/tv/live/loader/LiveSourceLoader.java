package com.tv.live.loader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tv.live.Channel;
import com.tv.live.JsonLiveParser;  // 🟢 确保导入了您写的 JSON 解析器
import com.tv.live.PlaylistParser;
import com.tv.live.UrlConfig;
import com.tv.live.util.CacheManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * ✅ 直播源加载器（带缓存 + GitHub 智能加速 + 完整3xx重定向处理 + JSON/M3U 自动识别）
 */
public class LiveSourceLoader {
    private static LiveSourceLoader instance;
    private final Context context;
    private final Handler mainHandler;
    private final CacheManager cacheManager;

    // ====================================================================
    // ✅ GitHub 加速相关配置
    // ====================================================================
    public enum AccelerateType {
        JSDELIVR, GHPROXY, GITMIRROR, NONE
    }
    private AccelerateType accelerateType = AccelerateType.JSDELIVR;
    private boolean accelerateEnabled = true;

    // ====================================================================
    // 接口定义
    // ====================================================================
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

    public void setAccelerateEnabled(boolean enabled) {
        this.accelerateEnabled = enabled;
    }

    public void setAccelerateType(AccelerateType type) {
        this.accelerateType = type;
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

    // ====================================================================
    // 加载直播源（核心修改位置）
    // ====================================================================
    public void load(LoadCallback callback) {
        new Thread(() -> {
            try {
                String originalUrl = UrlConfig.LIVE_URL;
                // ✅ GitHub 智能加速
                String acceleratedUrl = getAcceleratedUrl(originalUrl);

                // 1. ✅ 下载原始内容（用您已有的 downloadRawContent 方法，只请求一次网络）
                String rawContent = downloadRawContent(acceleratedUrl);

                if (rawContent == null || rawContent.isEmpty()) {
                    mainHandler.post(() -> callback.onError("下载直播源失败：内容为空"));
                    return;
                }

                // 2. 保存到缓存
                cacheManager.saveFileCache("live_source", rawContent);

                // ================================================================
                // 🟢【核心修复】：根据内容类型自动选择解析器
                // ================================================================
                List<Channel> channels;
                String trimmed = rawContent.trim();

                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    // ✅ 如果是 JSON 格式，调用您写好的 JsonLiveParser
                    try {
                        channels = JsonLiveParser.parseContent(rawContent);
                    } catch (Exception e) {
                        e.printStackTrace();
                        mainHandler.post(() -> callback.onError("JSON 解析失败：" + e.getMessage()));
                        return;
                    }
                } else {
                    // ✅ 否则按原本的 M3U 格式解析
                    channels = PlaylistParser.parseContent(rawContent);
                }
                // ================================================================

                // 3. 回调结果
                final List<Channel> finalChannels = channels;
                mainHandler.post(() -> callback.onSuccess(finalChannels));

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    // ====================================================================
    // ✅ GitHub 智能加速：获取加速后的 URL
    // ====================================================================
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
            case NONE: default: return originalUrl;
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
            return "https://cdn.jsdelivr.net/gh/" + info.user + "/" + info.repo
                    + (info.branch != null && !info.branch.isEmpty() ? "@" + info.branch : "")
                    + "/" + info.path;
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private String convertToGhproxy(String githubUrl) {
        try {
            return "https://ghproxy.com/" + githubUrl;
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private String convertToGitmirror(String githubUrl) {
        try {
            return githubUrl.replace("raw.githubusercontent.com", "raw.gitmirror.com")
                    .replace("raw.github.com", "raw.gitmirror.com");
        } catch (Exception e) {
            return githubUrl;
        }
    }

    private static class GitHubUrlInfo {
        String user, repo, branch, path;
    }

    private GitHubUrlInfo parseGitHubUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            GitHubUrlInfo info = new GitHubUrlInfo();
            String cleanUrl = url;
            if (cleanUrl.startsWith("https://")) cleanUrl = cleanUrl.substring(8);
            else if (cleanUrl.startsWith("http://")) cleanUrl = cleanUrl.substring(7);

            if (cleanUrl.startsWith("raw.githubusercontent.com/")) {
                String[] parts = cleanUrl.substring("raw.githubusercontent.com/".length()).split("/", 4);
                if (parts.length >= 4) {
                    info.user = parts[0];
                    info.repo = parts[1];
                    info.branch = parts[2];
                    info.path = parts[3];
                    return info;
                }
            }
            if (cleanUrl.startsWith("github.com/") && cleanUrl.contains("/raw/")) {
                Pattern p = Pattern.compile("github\\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)");
                Matcher m = p.matcher(cleanUrl);
                if (m.find()) {
                    info.user = m.group(1);
                    info.repo = m.group(2);
                    info.branch = m.group(3);
                    info.path = m.group(4);
                    return info;
                }
            }
            if (cleanUrl.startsWith("raw.github.com/")) {
                String[] parts = cleanUrl.substring("raw.github.com/".length()).split("/", 4);
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
            return null;
        }
    }

    // ====================================================================
    // 下载原始内容【完整修复重定向】
    // ====================================================================
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
                    if (location == null || location.isEmpty()) return null;
                    if (!location.startsWith("http")) {
                        URL baseUrl = new URL(currentUrl);
                        currentUrl = new URL(baseUrl, location).toString();
                    } else {
                        currentUrl = location;
                    }
                    conn.disconnect();
                    conn = null;
                    if (redirectCount >= MAX_REDIRECT) return null;
                    continue;
                }
                if (responseCode != 200) return null;

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
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
