package com.tv.live;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
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
 * 带重定向日志的 HTTP 数据源（极致降噪 + 轻量频道名显示）
 * 日志规则：
 * 1. 有重定向：完整打印（含频道名）
 * 2. 无重定向 + .ts 分片：完全静默
 * 3. 无重定向 + .m3u8 主列表：仅打印一行轻量级 "开始播放（频道名）: url"
 */
public class RedirectLoggingHttpDataSource extends BaseDataSource implements HttpDataSource {

    private static final String TAG = "RedirectHttp";
    private static final int MAX_REDIRECTS = 20;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;

    private final Map<String, String> defaultRequestProperties;
    private final boolean allowCrossProtocolRedirects;

    private HttpURLConnection connection;
    private InputStream inputStream;
    private boolean opened;
    private long bytesToRead;
    private long bytesRead;
    private int responseCode = -1;

    private String currentChannelName = "";

    private String getTimeStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    protected RedirectLoggingHttpDataSource(
            Map<String, String> defaultRequestProperties,
            boolean allowCrossProtocolRedirects) {
        super(true);
        this.defaultRequestProperties = defaultRequestProperties != null
                ? new HashMap<>(defaultRequestProperties)
                : new HashMap<>();
        this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
    }

    public void setChannelName(String channelName) {
        this.currentChannelName = (channelName != null) ? channelName : "";
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSource.HttpDataSourceException {
        try {
            transferInitializing(dataSpec);
            connection = openConnection(dataSpec);
            responseCode = connection.getResponseCode();

            if (responseCode < 20 || responseCode > 299) {
                String responseMessage = connection.getResponseMessage();
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: HTTP " + responseCode + " " + responseMessage);
                throw new HttpDataSource.HttpDataSourceException(
                        "HTTP " + responseCode + " " + responseMessage,
                        dataSpec,
                        HttpDataSource.HttpDataSourceException.TYPE_OPEN);
            }

            try {
                inputStream = connection.getInputStream();
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                }
            } catch (IOException e) {
                inputStream = connection.getErrorStream();
            }

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

    private HttpURLConnection openConnection(DataSpec dataSpec) throws IOException {
        String originalUrl = dataSpec.uri.toString();
        String currentUrl = originalUrl;
        int redirectCount = 0;

        while (true) {
            if (redirectCount > MAX_REDIRECTS) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 重定向次数超过限制（" + MAX_REDIRECTS + "次）");
                throw new IOException("Too many redirects");
            }

            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);

            for (Map.Entry<String, String> entry : defaultRequestProperties.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if (dataSpec.position != C.POSITION_UNSET) {
                String rangeValue = "bytes=" + dataSpec.position + "-";
                if (dataSpec.length != C.LENGTH_UNSET) {
                    rangeValue += (dataSpec.position + dataSpec.length - 1);
                }
                conn.setRequestProperty("Range", rangeValue);
            }

            int respCode = conn.getResponseCode();
            boolean isRedirect = (respCode == 301 || respCode == 302
                    || respCode == 303 || respCode == 307 || respCode == 308);

            if (!isRedirect) {
                String time = getTimeStr();
                String channelInfo = (!currentChannelName.isEmpty()) ? "（" + currentChannelName + "）" : "";
                String urlLower = currentUrl.toLowerCase();

                if (redirectCount > 0) {
                    // 【重定向追踪】完整打印
                    SettingsActivity.log("[" + time + "] 开始播放" + channelInfo + ": " + originalUrl);
                    SettingsActivity.log("[" + time + "] ✅ 解析完成，共" + redirectCount + "次跳转");
                    SettingsActivity.log("[" + time + "] ✅ 最终响应: HTTP " + respCode);
                } else if (urlLower.contains(".m3u8")) {
                    // 【无重定向 + 主列表】只打印一行，既能看见频道名，又不会刷屏
                    SettingsActivity.log("[" + time + "] 开始播放" + channelInfo + ": " + currentUrl);
                }
                // 【无重定向 + .ts分片】完全静默，一行都不打印
                return conn;
            }

            // ===== 处理重定向 =====
            redirectCount++;
            String location = conn.getHeaderField("Location");

            if (TextUtils.isEmpty(location)) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 第 " + redirectCount + " 次重定向没有 Location 头");
                conn.disconnect();
                throw new IOException("Redirect with no Location header");
            }

            String redirectUrl = resolveRedirectUrl(currentUrl, location);

            boolean isCrossProtocol = !url.getProtocol().equalsIgnoreCase(
                    Uri.parse(redirectUrl).getScheme());
            if (isCrossProtocol && !allowCrossProtocolRedirects) {
                SettingsActivity.log("[" + getTimeStr() + "] ❌ 失败: 跨协议重定向被禁止");
                conn.disconnect();
                throw new IOException("Cross-protocol redirect not allowed");
            }

            String time = getTimeStr();
            SettingsActivity.log("[" + time + "] 第" + redirectCount + "次重定向到: " + redirectUrl);

            conn.disconnect();
            currentUrl = redirectUrl;
        }
    }

    private String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }

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
            sb.append(location);
        } else {
            if (path != null && path.contains("/")) {
                String parentPath = path.substring(0, path.lastIndexOf('/') + 1);
                sb.append(parentPath).append(location);
            } else {
                sb.append("/").append(location);
            }
        }
        return sb.toString();
    }

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
                if (bytesToRead != C.LENGTH_UNSET && bytesRead != bytesToRead) {
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

    public static final class Factory implements HttpDataSource.Factory {
        private final Map<String, String> defaultRequestProperties;
        private boolean allowCrossProtocolRedirects;
        private String channelName = "";

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

        public Factory setChannelName(String name) {
            this.channelName = (name != null) ? name : "";
            return this;
        }

        @Override
        public HttpDataSource createDataSource() {
            RedirectLoggingHttpDataSource dataSource = new RedirectLoggingHttpDataSource(
                    defaultRequestProperties,
                    allowCrossProtocolRedirects);
            dataSource.setChannelName(this.channelName);
            return dataSource;
        }
    }
}
