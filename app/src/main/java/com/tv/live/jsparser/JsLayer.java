package com.tv.live.jsparser;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JsLayer {
    static AssetManager assetManager = null;
    static String filesDirPath = null;
    static WebView webView = null;
    static Context mContext = null;
    static OkHttpClient okHttpClient = null;
    static JsCallback pendingCallback = null;

    public interface JsCallback {
        void onResult(String result);
        void onError(String error);
    }

    public static void init(Context context) {
        webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new ParserJsInterface(), "client");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
            }
        });
        assetManager = context.getAssets();
        filesDirPath = context.getFilesDir().getPath() + "/";
        mContext = context;
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        webView.loadDataWithBaseURL("file:///android_asset/", "<html><body></body></html>", "text/html", "UTF-8", null);
    }

    public static boolean isInit() {
        return webView != null && assetManager != null;
    }

    public static void evaluate(String str, final JsCallback jsCallback) {
        if (webView == null || assetManager == null) {
            jsCallback.onError("not initialized");
            return;
        }
        try {
            pendingCallback = jsCallback;
            String assetFileToString = assetFileToString("js/native_layer.js");
            String fullScript = assetFileToString + str;
            webView.post(() -> {
                webView.evaluateJavascript(fullScript, value -> {
                    if (value != null && !"null".equals(value)) {
                        pendingCallback.onResult(value.replace("\"", ""));
                    } else {
                        pendingCallback.onError("no result");
                    }
                });
            });
        } catch (Exception e) {
            jsCallback.onError("internal error");
            e.printStackTrace();
        }
    }

    public static void release() {
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
    }

    public static String assetFileToString(String str) {
        try {
            InputStream is = assetManager.open(str);
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    static class ParserJsInterface {
        @JavascriptInterface
        public String getUrlContent(String str, String str2) {
            return getHttp(str, str2).syncGet();
        }

        @JavascriptInterface
        public String getRespAndHeaders(String str, String str2) {
            return getHttp(str, str2).syncGetRespWithHeaders();
        }

        @JavascriptInterface
        public String postUrlContent(String str, String str2, String str3, String str4) {
            return getHttp(str, str3, str2).syncPost(str4);
        }

        @JavascriptInterface
        public String getMD5(String str) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(str.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String writeFile(String str, String str2) {
            try {
                String str3 = JsLayer.filesDirPath + str2;
                FileOutputStream fileOutputStream = new FileOutputStream(new File(str3));
                fileOutputStream.write(str.getBytes());
                fileOutputStream.close();
                return str3;
            } catch (IOException e) {
                e.printStackTrace();
                return "";
            }
        }

        @JavascriptInterface
        public String AESDecrypt128(String str, String str2) {
            try {
                byte[] decode = Base64.decode(str.getBytes(), 0);
                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(2, new SecretKeySpec(str2.getBytes(), "AES"));
                return new String(cipher.doFinal(decode));
            } catch (Throwable th) {
                th.printStackTrace();
                return "";
            }
        }

        @JavascriptInterface
        public String getLocation(String str) throws Exception {
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
            httpURLConnection.setInstanceFollowRedirects(false);
            httpURLConnection.setConnectTimeout(5000);
            return httpURLConnection.getHeaderField("Location");
        }

        private HttpUtil getHttp(String str, String str2) {
            return getHttpBuilder(str, str2).build();
        }

        private HttpUtil getHttp(String str, String str2, String str3) {
            return getHttpBuilder(str, str2).postBody(str3).build();
        }

        private HttpUtil.Builder getHttpBuilder(String str, String str2) {
            HashMap<String, String> headers = JSON.parseObject(str2, new TypeReference<HashMap<String, String>>() {});
            return new HttpUtil.Builder(str).setHeaders(headers);
        }
    }

    public static class HttpUtil {
        private String url;
        private HashMap<String, String> headers;
        private String postBody;

        private HttpUtil(Builder builder) {
            this.url = builder.url;
            this.headers = builder.headers;
            this.postBody = builder.postBody;
        }

        public String syncGet() {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url).get();
                if (headers != null) {
                    for (HashMap.Entry<String, String> entry : headers.entrySet()) {
                        requestBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                }
                Request request = requestBuilder.build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return response.body().string();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }

        public String syncGetRespWithHeaders() {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url).get();
                if (headers != null) {
                    for (HashMap.Entry<String, String> entry : headers.entrySet()) {
                        requestBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                }
                Request request = requestBuilder.build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("{\"headers\":{");
                        boolean first = true;
                        for (String name : response.headers().names()) {
                            if (!first) sb.append(",");
                            sb.append("\"").append(name).append("\":\"").append(response.header(name)).append("\"");
                            first = false;
                        }
                        sb.append("},\"body\":\"").append(response.body().string()).append("\"}");
                        return sb.toString();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }

        public String syncPost(String body) {
            try {
                Request.Builder requestBuilder = new Request.Builder().url(url);
                if (headers != null) {
                    for (HashMap.Entry<String, String> entry : headers.entrySet()) {
                        requestBuilder.addHeader(entry.getKey(), entry.getValue());
                    }
                }
                okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/json"));
                Request request = requestBuilder.post(requestBody).build();
                try (Response response = okHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return response.body().string();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }

        public static class Builder {
            private String url;
            private HashMap<String, String> headers;
            private String postBody;

            public Builder(String url) {
                this.url = url;
            }

            public Builder setHeaders(HashMap<String, String> headers) {
                this.headers = headers;
                return this;
            }

            public Builder postBody(String postBody) {
                this.postBody = postBody;
                return this;
            }

            public HttpUtil build() {
                return new HttpUtil(this);
            }
        }
    }
}