package com.tv.live;

import android.net.Uri;
import android.text.TextUtils;

// ====================================================================
// ✅ 2026-06-23 修改：升级到 Media3 1.10.1
// ====================================================================
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 带重定向日志的 HTTP 数据源（格式对齐修正版）
 *
 * 【功能】
 * 1. 手动处理 HTTP 重定向（301/302/303/307/308）
 * 2. 支持跨协议重定向（HTTP ↔ HTTPS）
 * 3. 支持相对路径的 Location
 * 4. 支持 GZIP 解压
 * 5. ✅ 日志格式完全匹配用户截图：带 [HH:mm:ss] 时间戳，详细步骤
 */
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {

    private static final String TAG = "RedirectHttp";
    /** 最大重定向次数，防止无限循环 */
    private static final int MAX_REDIRECTS = 20;
    /** 连接超时时间（毫秒） */
    private static final int CONNECT_TIMEOUT = 5000;
    /** 读取超时时间（毫秒） */
    private static final int READ_TIMEOUT = 15000;

    /** 默认请求头 */
    private final Map<String, String> defaultRequestProperties;
    /** 是否允许跨协议重定向 */
    private final boolean allowCrossProtocolRedirects;

    /** 当前 HTTP 连接 */
    private HttpURLConnection connection;
    /** 输入流 */
    private InputStream inputStream;
    /** 是否已经打开 */
    private boolean opened;
    /** 当前请求的字节数 */
    private long bytesToRead;
    /** 已读取的字节数 */
    private long bytesRead;
    /** HTTP 响应状态码（用于 getResponseCode()） */
    private int responseCode = -1;

    // ====================================================================
    // 时间戳格式化工具
    // ====================================================================
    private String getTimeStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    // ====================================================================
    // 构造函数
    // ====================================================================
    protected RedirectLoggingHttpDataSource(
            Map<String, String> defaultRequestProperties,
            boolean allowCrossProtocolRedirects) {
        super(true);
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<>();
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
    }

    // ====================================================================
    // open 方法
    // ====================================================================
    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        try {
            transferInitializing(dataSpec);

            // ===== 打开连接（手动处理重定向） =====
            connection = openConnection(dataSpec);
            responseCode = connection.getResponseCode();  // 保存状态码

            // ===== 处理错误响应 =====
            if (responseCode < 20 || responseCode > 299) {
                String responseMessage = connection.getResponseMessage();
                // ✅ 错误日志带时间戳
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: HTTP " + responseCode + " " + responseMessage);
                throw new HttpDataSource.HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }

            // ===== 获取输入流 =====
            try {
                inputStream = connection.getInputStream();
                // 处理 GZIP 压缩
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                }
            } catch (IOException e) {
                // 如果获取输入流失败，尝试用错误流
                inputStream = connection.getErrorStream();
            }

            // ===== 计算要读取的字节数 =====
            long contentLength = getContentLength(connection);
            if (dataSpec.position != C.POSITION_UNSET) {
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : (contentLength != C.LENGTH_UNSET ? contentLength - dataSpec.position : C.LENGTH_UNSET);
            } else {
                bytesToRead = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : contentLength;
            }
            bytesRead = 0;
            opened = true;
            transferStarted(dataSpec);
            return bytesToRead;

        } catch (IOException e) {
            closeConnectionQuietly();
            throw new HttpDataSource.HttpDataSourceException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
    }

    // ====================================================================
    // 打开连接，手动处理重定向（核心日志改造区域）
    // ====================================================================
    private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
        String currentUrl = dataSpec.uri.toString();

        // ✅ 【新增】打印原始 URL
        SettingsActivity.log("[" + getTimeStr() + "] 原始URL: " + currentUrl);

        int redirectCount = 0;

        while (true) {
            // ===== 检查重定向次数 =====
            if (redirectCount > MAX_REDIRECTS) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 重定向次数超过限制（" + MAX_REDIRECTS + "次）");
                throw new IOException("Too many redirects");
            }

            // ===== 创建连接 =====
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.setInstanceFollowRedirects(false);  // 关键：不自动跟随重定向，我们手动处理
            conn.setUseCaches(false);

            // ===== 设置请求头 =====
            for (Map.Entry<String, String> entry : defaultRequestProperties.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            // ===== 设置 Range（如果有） =====
            if (dataSpec.position != C.POSITION_UNSET) {
                String rangeValue = "bytes=" + dataSpec.position + "-";
                if (dataSpec.length != C.LENGTH_UNSET) {
                    rangeValue += (dataSpec.position + dataSpec.length - 1);
                }
                conn.setRequestProperty("Range", rangeValue);
            }

            // ===== 发起请求 =====
            int respCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();

            // ===== 判断是否是重定向 =====
            boolean isRedirect = (respCode == 301 || respCode == 302
                    || respCode == 303 || respCode == 307 || respCode == 308);

            if (!isRedirect) {
                String time = getTimeStr();
                // ✅ 【新增】解析完成，共 N 次跳转
                if (redirectCount > 0) {
                    SettingsActivity.log("[" + time + "] ✅ 解析完成，共" + redirectCount + "次跳转");
                }
                // ✅ 【新增】最终响应（替换掉旧版单行日志）
                SettingsActivity.log("[" + time + "] ✅ 最终响应: HTTP " + respCode);
                return conn;
            }

            // ===== 处理重定向 =====
            redirectCount++;
            String location = conn.getHeaderField("Location");

            if (TextUtils.isEmpty(location)) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 第 " + redirectCount + " 重定向没有 Location 头");
                conn.disconnect();
                throw new IOException("Redirect with no Location header");
            }

            // ===== 处理相对路径 =====
            String redirectUrl = resolveRedirectUrl(currentUrl, location);

            // ===== 检查跨协议 =====
            boolean isCrossProtocol = !url.getProtocol().equalsIgnoreCase(
                    Uri.parse(redirectUrl).getScheme());
            if (isCrossProtocol && !allowCrossProtocolRedirects) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 跨协议重定向被禁止");
                conn.disconnect();
                throw new IOException("Cross-protocol redirect not allowed");
            }

            // ✅ 【新增】详细记录重定向步骤（精确对齐截图格式）
            String time = getTimeStr();
            SettingsActivity.log("[" + time + "] 第" + redirectCount + "次: HTTP " + respCode + " → " + location);
            SettingsActivity.log("[" + time + "] 重定向到: " + location);

            // ===== 关闭当前连接，准备下一次请求 =====
            conn.disconnect();
            currentUrl = redirectUrl;
        }
    }

    // ====================================================================
    // 解析重定向地址（处理相对路径）
    // ====================================================================
    private String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        // 如果 location 已经是完整 URL，直接返回
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }

        // 相对路径，需要拼接
        Uri baseUri = Uri.parse(baseUrl);
        String scheme = baseUri.getScheme();
        String host = baseUri.getHost();
        int port = baseUri.getPort();
        String path = baseUri.getPath();

        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1 && port != 80 && port != 443) {
            sb.append(":").append(port);
        }

        if (location.startsWith("/")) {
            // 绝对路径（相对于域名）
            sb.append(location);
        } else {
            // 相对路径（相对于当前路径）
            if (path != null && path.contains("/")) {
                String parentPath = path.substring(0, path.lastIndexOf('/') + 1);
                sb.append(parentPath).append(location);
            } else {
                sb.append("/").append(location);
            }
        }
        return sb.toString();
    }

    // ====================================================================
    // 获取 Content-Length
    // ====================================================================
    private long getContentLength(HttpURLConnection connection) {
        String contentLength = connection.getHeaderField("Content-Length");
        if (!TextUtils.isEmpty(contentLength)) {
            try {
                return Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        return C.LENGTH_UNSET;
    }

    // ====================================================================
    // read 方法
    // ====================================================================
    @Override
    public int read(byte[] buffer, int offset, int readLength) throws HttpDataSource.HttpDataSourceException {
        if (readLength == 0) {
            return 0;
        }
        if (bytesToRead == 0) {
            return C.RESULT_END_OF_INPUT;
        }

        try {
            int bytesToReadThisTime = (int) Math.min(
                    readLength,
                    bytesToRead == C.LENGTH_UNSET ? Integer.MAX_VALUE : bytesToRead - bytesRead);
            int bytesReadThisTime = inputStream.read(buffer, offset, bytesToReadThisTime);

            if (bytesReadThisTime == -1) {
                // 读取结束
                if (bytesToRead != C.LENGTH_UNSET && bytesRead != bytesToRead) {
                    // 读取的字节数和预期不符
                    throw new HttpDataSource.HttpDataSourceException(
                            "Unexpected end of input",
                            new DataSpec(Uri.parse(connection.getURL().toString())),
                            HttpDataSource.HttpDataSourceException.TYPE_READ);
                }
                return C.RESULT_END_OF_INPUT;
            }
            bytesRead += bytesReadThisTime;
            bytesTransferred(bytesReadThisTime);
            return bytesReadThisTime;
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e,
                    new DataSpec(Uri.parse(connection.getURL().toString())),
                    HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
    }

    // ====================================================================
    // 其他接口方法
    // ====================================================================
    @Override
    public Uri getUri() {
        return connection == null ? null : Uri.parse(connection.getURL().toString());
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? null : connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        defaultRequestProperties.put(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        defaultRequestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        defaultRequestProperties.clear();
    }

    @Override
    public void close() throws HttpDataSource.HttpDataSourceException {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
    }

    private void closeConnectionQuietly() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                // 忽略
            }
            inputStream = null;
        }
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    // ====================================================================
    // Factory 工厂类
    // ====================================================================
    public static final class Factory implements HttpDataSource.Factory {
        private final Map<String, String> defaultRequestProperties;
        private boolean allowCrossProtocolRedirects;

        public Factory() {
            this.defaultRequestProperties = new HashMap<>();
            this.allowCrossProtocolRedirects = true;
        }

        public Factory setDefaultRequestProperties(Map<String, String> requestProperties) {
            defaultRequestProperties.clear();
            if (requestProperties != null) {
                defaultRequestProperties.putAll(requestProperties);
            }
            return this;
        }

        public Factory setAllowCrossProtocolRedirects(boolean allow) {
            this.allowCrossProtocolRedirects = allow;
            return this;
        }

        @Override
        public HttpDataSource createDataSource() {
            return new RedirectLoggingHttpDataSource(
                    defaultRequestProperties,
                    allowCrossProtocolRedirects);
        }
    }
}
