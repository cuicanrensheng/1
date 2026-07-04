package com.tv.live.util;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NetUtil {
    private static volatile NetUtil sInstance;
    private final OkHttpClient mClient;
    // OkHttp原生UA（公网虎牙/斗鱼专用）
    private static final String OKHTTP_UA = "okhttp/4.9.3";
    // 移动IPTV机顶盒标准UA（内网PLTV源专用，运营商白名单）
    private static final String IPTV_STB_UA = "Mozilla/5.0 (Linux; Android 9; STB-HW-IPTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.132 Safari/537.36 STB/IPTV-JX-CM";

    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;

    private NetUtil() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static NetUtil getInstance() {
        if (sInstance == null) {
            synchronized (NetUtil.class) {
                if (sInstance == null) sInstance = new NetUtil();
            }
        }
        return sInstance;
    }

    /**
     * 统一生成请求头，自动区分公网/移动内网机顶盒UA
     */
    public Headers createCommonHeaders(String url) {
        Map<String, String> headerMap = new HashMap<>();
        String targetUA;
        // 判断是否为移动内网PLTV直播源，切换机顶盒UA
        if (isMobileInnerPltvUrl(url)) {
            targetUA = IPTV_STB_UA;
        } else {
            targetUA = OKHTTP_UA;
        }

        // 基础通用加固请求头（所有域名必带）
        headerMap.put("User-Agent", targetUA);
        headerMap.put("Accept", "*/*");
        headerMap.put("Connection", "keep-alive");
        headerMap.put("Icy-MetaData", "1");
        headerMap.put("Accept-Language", "zh-CN,zh;q=0.9");
        headerMap.put("Cache-Control", "no-cache");

        // 提取Host字段，移动内网源必备校验头，缺失极易403
        String host = extractHostFromUrl(url);
        if (host != null) {
            headerMap.put("Host", host);
        }

        // 虎牙/斗鱼公网域名补充防盗链 Referer、Origin
        if (url.contains("huya.com")) {
            headerMap.put("Referer", "https://www.huya.com/");
            headerMap.put("Origin", "https://www.huya.com");
        } else if (url.contains("douyu.com")) {
            headerMap.put("Referer", "https://www.douyu.com/");
            headerMap.put("Origin", "https://www.douyu.com");
        }

        return Headers.of(headerMap);
    }

    /**
     * 判断是否江西移动内网PLTV源（适配117内网IP、hwrr.jx.chinamobile.com域名）
     */
    private boolean isMobileInnerPltvUrl(String url) {
        if (url == null) return false;
        return url.startsWith("http://117.")
                || url.contains("hwrr.jx.chinamobile.com")
                || url.contains(".chinamobile.com") && url.contains("PLTV");
    }

    /**
     * 从URL自动提取Host，填充Host请求头（移动内网关键校验字段）
     */
    private String extractHostFromUrl(String url) {
        try {
            int start = url.indexOf("://") + 3;
            int end = url.indexOf("/", start);
            if (end == -1) end = url.indexOf("?", start);
            if (end == -1) end = url.length();
            return url.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    // 虎牙专用固定头（解析接口调用）
    public Headers createHuyaFixedHeaders() {
        Map<String, String> map = new HashMap<>();
        map.put("User-Agent", OKHTTP_UA);
        map.put("Referer", "https://www.huya.com/");
        map.put("Origin", "https://www.huya.com");
        map.put("Accept-Language", "zh-CN,zh;q=0.9");
        return Headers.of(map);
    }

    // 同步GET请求方法（原有逻辑不变）
    public Response syncGet(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        return mClient.newCall(request).execute();
    }

    public String syncGetText(String url) throws IOException {
        try (Response response = syncGet(url)) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " 访问拒绝");
            }
            return response.body().string();
        }
    }

    public OkHttpClient getClient() {
        return mClient;
    }
}
