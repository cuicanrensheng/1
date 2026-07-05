package com.tv.live.util;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.tv.live.SettingsActivity; // 🟢 导入日志工具

/**
 * 统一网络工具（合并HttpUtil + RequestHeaderUtil）
 * 全局统一ExoPlayer UA，解析器、播放器共用一套请求指纹，降低403拦截
 */
public class NetUtil {
    private static volatile NetUtil sInstance;
    // 🟢 用于读取 SharedPreferences 的全局上下文
    private static Context sAppContext;
    private final OkHttpClient mClient;
    
    private static final long CONNECT_TIMEOUT = 10000L;
    private static final long READ_TIMEOUT = 15000L;
    private static final long WRITE_TIMEOUT = 10000L;

    // 🟢 静态初始化方法，在 Application 中调用
    public static void init(Context context) {
        sAppContext = context.getApplicationContext();
    }

    private NetUtil() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                // 🟢【关键】拦截器强制发送 "Accept-Encoding: identity" 
                // 防止虎牙 Tengine 因为默认的 gzip 编码而拦截 ExoPlayer 请求
                .addNetworkInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request request = chain.request();
                        Request newRequest = request.newBuilder()
                                .header("Accept-Encoding", "identity")
                                .build();
                        return chain.proceed(newRequest);
                    }
                })
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

        // 🟢 核心新增：从设置中动态读取 UA，默认 "exo" (ExoPlayer)
        String userAgent = "ExoPlayer";
        if (sAppContext != null) {
            SharedPreferences sp = sAppContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            String uaMode = sp.getString("user_agent_mode", "exo"); 
            if ("vlc".equals(uaMode)) {
                userAgent = "VLC/3.0.18 LibVLC/3.0.18";
            }
        }
        
        // 🟢【记录日志】将实际使用的 UA 打印到解析日志中，方便确认切换是否生效
        SettingsActivity.log("【UA检测】当前正在使用的请求头 User-Agent: " + userAgent);

        headerMap.put("User-Agent", userAgent);
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
