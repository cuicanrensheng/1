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
 * 全局OkHttp单例，统一PC浏览器请求头，全平台解析共用
 * 内置超时、通用GET、403识别、域名自动匹配Referer/Origin
 */
public class NetUtil {
    // 单例实例
    private static Net sInstance;
    private final OkHttpClient mClient;
    // PC浏览器固定UA，全局统一，一处修改全部生效
    private static final String PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    // 超时配置
    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;
    private static final long WRITE_TIMEOUT = 10000L;

    private NetUtil() {
        // 全局唯一OkHttpClient，避免多实例占用内存
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

    // ===================== 请求头统一生成（原RequestHeaderUtil能力） =====================
    /**
     * 根据url自动匹配对应平台Referer/Origin，全项目统一UA
     */


    /**
     * 仅虎牙专用固定请求头（HuyaParser专用，不用传url）
     */
    public Headers createHuyaFixedHeaders() {
        return createCommonHeaders("https://www.huya.com");
    }

    // ===================== 通用GET请求（原HttpUtil能力） =====================
    /**
     * 通用同步GET，返回Response，外部判断403等状态码
     */
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

    /**
     * GET并直接返回字符串，自动关闭流，抛出异常区分403拦截
     */
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

    /**
     * 获取全局OkHttpClient，播放器/特殊请求扩展使用
     */
    public OkHttpClient getClient() {
        return mClient;
    }
}
    public Headers createCommonHeaders(String url) {
    Map<String, String> headerMap = new HashMap<>();
    headerMap.put("User-Agent", PC_USER_AGENT);
    headerMap.put("Accept", "*");
    headerMap.put("Connection", "keep-alive");
    headerMap.put("Icy-MetaData", "1");
    headerMap.put("Accept-Language", "zh-CN,zh;q=0.9");

    String referer;
    String origin;
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
