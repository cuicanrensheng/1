package com.tv.live.loader;

import android.util.Log;
import com.tv.live.util.NetUtil;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Response;

public class HuyaStreamParser {
    private static final String TAG = "HuyaStreamParser";
    // 虎牙H5请求头，防止403拦截
    public static final okhttp3.RequestHeaders HUYA_H5_HEADERS = new okhttp3.RequestHeaders.Builder()
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/118.0.0.0 Mobile")
            .add("Referer", "https://m.huya.com/")
            .add("Origin", "https://m.huya.com")
            .build();

    /**
     * 根据roomId解析一起看影视m3u8播放地址
     * @param roomId 房间ID
     * @return 可播放m3u8链接，失败返回null
     */
    public static String resolveM3u8(String roomId) {
        Response response = null;
        try {
            String roomPageUrl = "https://m.huya.com/" + roomId;
            response = NetUtil.getInstance().syncGet(roomPageUrl, HUYA_H5_HEADERS);
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "房间页请求失败 roomId=" + roomId + " code=" + response.code());
                return null;
            }
            String html = response.body().string();

            // 提取window.streamConfig = { ... }
            Pattern pattern = Pattern.compile("window\\.streamConfig\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            if (!matcher.find()) {
                Log.w(TAG, "未匹配到streamConfig roomId=" + roomId);
                return null;
            }
            String jsonStr = matcher.group(1);
            JSONObject cfg = new JSONObject(jsonStr);

            String seqid = cfg.optString("seqid");
            String streamName = cfg.optString("streamName");
            String secretSalt = cfg.optString("secretSalt");
            if (seqid.isEmpty() || streamName.isEmpty() || secretSalt.isEmpty()) {
                Log.w(TAG, "播放参数缺失 roomId=" + roomId);
                return null;
            }

            // 10位时间戳
            long timeSec = System.currentTimeMillis() / 1000;
            String signRaw = timeSec + seqid + secretSalt;
            String wsSecret = md5(signRaw);

            // 拼接最终m3u8
            String m3u8 = "https://al.hls.huya.com/src/" + streamName + ".m3u8"
                    + "?wsSecret=" + wsSecret
                    + "&wsTime=" + timeSec
                    + "&seqid=" + seqid;
            return m3u8;
        } catch (Exception e) {
            Log.e(TAG, "解析流异常 roomId=" + roomId, e);
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    // MD5 32位小写加密
    private static String md5(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] bytes = md.digest(text.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
}
