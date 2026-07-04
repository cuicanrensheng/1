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

/**
 * 统一网络工具（合并HttpUtil + RequestHeaderUtil）
 * 全局统一ExoPlayer UA，解析器、播放器共用一套请求指纹，降低403拦截
 */
public class NetUtil {
    private static volatile NetUtil sInstance;
    private final OkHttpClient mClient;
    
    // 全局统一 ExoPlayer UA，所有接口、拉流全部共用
    private static final String PC_USER_AGENT = "ExoPlayer"; 
    
    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;
    private static final long WRITE_TIMEOUT = 10000L;

    private NetUtil() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public static NetUtil getInstance() {
        if (sInstance == null) {
            synchronized (NetUtil.class) {
                if (sInstance == null) {
                    sInstance = new NetUtil();
                }
            }
        }
        return sInstance;
    }

    /** 根据URL自动生成虎牙/斗鱼适配请求头 */
    public Headers createCommonHeaders(String url) {
        Map<String, String> headerMap = new HashMap<>();
        headerMap.put("User-Agent", PC_USER_AGENT);
        headerMap.put("Accept", "*");
        headerMap.put("Connection", "keep-alive");
        headerMap.put("Icy-MetaData", "1");
        headerMap.put("Accept-Language", "zh-CN,zh;q=0.9");

        String referer, origin;
        if (url.contains("huya.com") || url.contains("huya.cn")) {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        } else if (url.contains("douyu.com") || url.contains("douyucdn.cn")) {
            referer = "https://www.douyu.com";
            origin = "https://www.douyu.com";
        } else {
            referer = "https://www.huya.com/";
            origin = "https://www.huya.com";
        }
        headerMap.put("Referer", referer);
        headerMap.put("Origin", origin);
        return Headers.of(headerMap);
    }

    /** 虎牙专用固定请求头，HuyaParser直接调用 */
    public Headers createHuyaFixedHeaders() {
        return createCommonHeaders("https://www.huya.com");
    }

    /** 同步GET，返回原始Response对象 */
    public Response syncGet(String url) throws IOException {
        Headers headers = createCommonHeaders(url);
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .get()
                .build();
        Call call = mClient.newCall(request);
        return call.execute();
    }

    /** GET请求，自动判断403并抛出拦截异常 */
    public String syncGetText(String url) throws IOException {
        try (Response response = syncGet(url)) {
            int code = response.code();
            if (code == 403) {
                throw new IOException("HTTP 403 防盗链拦截 url=" + url);
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("请求失败 code=" + code);
            }
            return response.body().string();
        }
    }

    /** 对外暴露全局OkHttpClient，供扩展使用 */
    public OkHttpClient getClient() {
        return mClient;
    }
}
