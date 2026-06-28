package com.tv.live;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import com.tv.live.util.CacheManager;
import java.lang.ref.WeakReference;

/**
 * EPG节目单管理器【内存泄漏完整修复版】
 */
public class EpgManager {
    private static EpgManager instance;
    private Map<String, List<Channel.EpgItem>> channelEpgMap = new HashMap<>();
    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false;
    private CacheManager cacheManager;
    private WeakReference<Context> ctxRef;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String CACHE_KEY_EPG = "epg";

    // ===================== 静态弱引用线程任务（消除匿名内部类泄漏） =====================
    // M3U提取EPG后台任务
    private static class M3uLoadTask implements Runnable {
        private final WeakReference<EpgManager> mgrRef;
        private final String m3uUrl;
        private final WeakReference<Runnable> callbackRef;
        public M3uLoadTask(EpgManager mgr, String url, Runnable cb) {
            mgrRef = new WeakReference<>(mgr);
            m3uUrl = url;
            callbackRef = new WeakReference<>(cb);
        }
        @Override
        public void run() {
            EpgManager manager = mgrRef.get();
            if (manager == null) return;
            String extractedEpgUrl = manager.extractEpgUrlFromM3u(m3uUrl);
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                SettingsActivity.log("【EPG】📡 从直播源获取到EPG地址：" + extractedEpgUrl);
                manager.epgUrl = extractedEpgUrl;
            } else {
                SettingsActivity.log("【EPG】📡 直播源未指定EPG地址，使用默认：" + manager.epgUrl);
            }
            Runnable cb = callbackRef.get();
            manager.loadEpg(cb);
        }
    }

    // 网络加载EPG完整后台任务
    private static class EpgLoadTask implements Runnable {
        private final WeakReference<EpgManager> mgrRef;
        private final WeakReference<Runnable> callbackRef;
        public EpgLoadTask(EpgManager mgr, Runnable cb) {
            mgrRef = new WeakReference<>(mgr);
            callbackRef = new WeakReference<>(cb);
        }
        @Override
        public void run() {
            EpgManager manager = mgrRef.get();
            if (manager == null) return;
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                URL url = new URL(manager.epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                in = conn.getInputStream();
                if (manager.epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }
                long savedBytes = manager.cacheManager.saveFileCache(CACHE_KEY_EPG, in);
                if (savedBytes <= 0) {
                    SettingsActivity.log("【EPG】❌ 保存缓存失败");
                    return;
                }
                SettingsActivity.log("【EPG】下载完成，大小：" + (savedBytes / 1024) + " KB");
                SettingsActivity.log("【EPG】缓存已保存（有效期24小时）");
                manager.hasPrintedSample = false;
                manager.channelEpgMap.clear();
                InputStream cacheIs = manager.cacheManager.getFileCacheStream(CACHE_KEY_EPG);
                if (cacheIs == null) {
                    SettingsActivity.log("【EPG】❌ 读取缓存失败");
                    return;
                }
                try {
                    manager.parseXml(cacheIs);
                } finally {
                    cacheIs.close();
                }
                SettingsActivity.log("【EPG】✅ 加载完成，共" + manager.channelEpgMap.size() + "个频道");
            } catch (Exception e) {
                SettingsActivity.log("【EPG】❌ 加载失败：" + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
            Runnable callback = callbackRef.get();
            if (callback != null && manager.mainHandler != null) {
                manager.mainHandler.post(callback);
            }
        }
    }

    // 单例入口
    public static EpgManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new EpgManager(ctx.getApplicationContext());
        }
        return instance;
    }
    public static EpgManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("EpgManager 未初始化，请先调用 getInstance(Context)");
        }
        return instance;
    }

    // 构造：弱引用包装ApplicationContext，移除强Context
    private EpgManager(Context ctx) {
        this.ctxRef = new WeakReference<>(ctx);
        this.cacheManager = CacheManager.getInstance(ctx);
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(new M3uLoadTask(this, m3uUrl, callback)).start();
    }

    private String extractEpgUrlFromM3u(String m3uUrl) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(m3uUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();
            InputStream is = conn.getInputStream();
            if (m3uUrl.endsWith(".gz")) {
                is = new GZIPInputStream(is);
            }
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 10) {
                lineCount++;
                if (line.contains("x-tvg-url") || line.contains("tvgtvg-url")) {
                    int start = line.indexOf("\"");
                    if (start >= 0) {
                        int end = line.indexOf("\"", start + 1);
                        if (end > start) {
                            return line.substring(start + 1, end).trim();
                        }
                    }
                    String[] parts = line.split("x-tvg-url=");
                    if (parts.length >= 2) {
                        String urlPart = parts[1].trim();
                        if (urlPart.startsWith("\"")) urlPart = urlPart.substring(1);
                        int spaceIdx = urlPart.indexOf(" ");
                        if (spaceIdx > 0) urlPart = urlPart.substring(0, spaceIdx);
                        if (urlPart.endsWith("\"")) urlPart = urlPart.substring(0, urlPart.length() - 1);
                        return urlPart.trim();
                    }
                }
            }
        } catch (Exception e) {
            SettingsActivity.log("【EPG】从M3U提取EPG地址失败：" + e.getMessage());
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadEpg(Runnable callback) {
        new Thread(new EpgLoadTask(this, callback)).start();
    }

    public boolean loadEpgFromCache() {
        Context ctx = ctxRef.get();
        try {
            InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
            if (cacheIs == null) {
                return false;
            }
            SettingsActivity.log("【EPG】从缓存加载...");
            hasPrintedSample = false;
            channelEpgMap.clear();
            try {
                parseXml(cacheIs);
            } finally {
                cacheIs.close();
            }
            SettingsActivity.log("【EPG】缓存加载完成，共" + channelEpgMap.size() + "个频道");
            return true;
        } catch (Exception e) {
            SettingsActivity.log("【EPG】缓存加载失败：" + e.getMessage());
            return false;
        }
    }

    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setLenient(true);
        Calendar todayCheck = Calendar.getInstance();
        SettingsActivity.log("【EPG】📅 今天日期：" + todayCheck.get(Calendar.YEAR) + "-"
                + (todayCheck.get(Calendar.MONTH) + 1) + "-" + todayCheck.get(Calendar.DAY_OF_MONTH)
                + "（周" + new String[]{"日","一","二","三","四","五","六"}[todayCheck.get(Calendar.DAY_OF_WEEK)-1] + "）");
        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();
        int programCount = 0;
        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();
                if ("channel".equals(tag)) {
                    currentChannelName = null;
                    tempPrograms.clear();
                }
                if ("display-name".equals(tag)) {
                    currentChannelName = xml.nextText().trim();
                }
                if ("programme".equals(tag)) {
                    String start = xml.getAttributeValue(null, "start");
                    String stop = xml.getAttributeValue(null, "stop");
                    if (start == null || stop == null) continue;
                    try {
                        String originalStart = start;
                        if (start.length() > 14) start = start.substring(0, 14);
                        if (stop.length() > 14) stop = stop.substring(0, 14);
                        Calendar startCal = Calendar.getInstance();
                        startCal.setTime(sdf.parse(start));
                        Calendar today = Calendar.getInstance();
                        String dayName = getDayName(startCal, today);
                        if (!hasPrintedSample && programCount < 5) {
                            SettingsActivity.log("【EPG】🔍 样本" + (programCount + 1)
                                    + "：原始时间=" + originalStart
                                    + "，解析日期=" + (startCal.get(Calendar.MONTH) + 1) + "月" + startCal.get(Calendar.DAY_OF_MONTH) + "日"
                                    + "，周" + new String[]{"日","一","二","三","四","五","六"}[startCal.get(Calendar.DAY_OF_WEEK)-1]
                                    + "，dayName=" + dayName);
                            programCount++;
                            if (programCount >= 5) hasPrintedSample = true;
                        }
                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);
                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);
                    } catch (Exception e) {
                        SettingsActivity.log("【EPG】跳过异常时间：" + start + "，错误：" + e.getMessage());
                    }
                }
                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }
            if (xml.getEventType() == XmlPullParser.END_TAG && "programme".equals(xml.getName())) {
                if (currentChannelName != null && !tempPrograms.isEmpty()) {
                    tempPrograms.sort(Comparator.comparing(item -> item.time));
                    channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                }
            }
            xml.next();
        }
        int count = 0;
        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            if (count >= 3) break;
            Set<String> days = new HashSet<>();
            for (Channel.EpgItem item : entry.getValue()) {
                days.add(item.dayName);
            }
            SettingsActivity.log("【EPG】频道【" + entry.getKey() + "】包含日期：" + days + "，节目数：" + entry.getValue().size());
            count++;
        }
    }

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }
        if (channelEpgMap.containsKey(channelName)) {
            SettingsActivity.log("【EPG】精确匹配成功：" + channelName);
            return channelEpgMap.get(channelName);
        }
        String cleanName = normalizeChannelName(channelName);
        String bestMatch = null;
        int bestScore = 0;
        for (Map.Entry<String, List<Channel.EpgItem>> entry : channelEpgMap.entrySet()) {
            String epgName = entry.getKey();
            String cleanEpgName = normalizeChannelName(epgName);
            int score = calculateMatchScore(cleanName, cleanEpgName);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = epgName;
            }
        }
        if (bestMatch != null && bestScore >= 20) {
            SettingsActivity.log("【EPG】模糊匹配成功：" + channelName
                    + " → " + bestMatch
                    + "（匹配度：" + bestScore + "分");
            return channelEpgMap.get(bestMatch);
        }
        SettingsActivity.log("【EPG】⚠️ 匹配失败：" + channelName
                + "（标准化后：" + cleanName + "）"
                + "，共尝试 " + channelEpgMap.size() + " 个频道");
        return new ArrayList<>();
    }

    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) return "";
        String result = name.toLowerCase();
        result = result.replaceAll("(?i)hd", "");
        result = result.replaceAll("(?i)fhd", "");
        result = result.replaceAll("(?i)uhd", "");
        result = result.replaceAll("(?i)sdtv", "");
        result = result.replaceAll("(?i)hdtv", "");
        result = result.replace("高清", "");
        result = result.replace("超清", "");
        result = result.replace("标清", "");
        result = result.replace("4k", "");
        result = result.replace("8k", "");
        result = result.replace(" ", "");
        result = result.replace("-", "");
        result = result.replace("_", "");
        result = result.replace(".", "");
        result = result.replace("·", "");
        result = result.replace(":", "");
        result = result.replace("：", "");
        result = result.replace("频道", "");
        result = result.replace("卫视", "");
        result = result.replace("电视台", "");
        result = result.replace("台", "");
        result = result.replace("传媒", "");
        result = result.replace("一套", "1套");
        result = result.replace("二套", "2套");
        result = result.replace("三套", "3套");
        result = result.replace("四套", "4套");
        result = result.replace("五套", "5套");
        result = result.replace("六套", "6套");
        result = result.replace("七套", "7套");
        result = result.replace("八套", "8套");
        result = result.replace("九套", "9套");
        result = result.replace("十套", "10套");
        result = result.replace("十一", "11");
        result = result.replace("十二", "12");
        result = result.replace("十三", "13");
        result = result.replace("十四", "14");
        result = result.replace("十五", "15");
        result = result.replace("cctv", "央视");
        return result;
    }

    private int calculateMatchScore(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) return 0;
        if (s1.equals(s2)) return 100;
        if (s1.contains(s2) || s2.contains(s1)) {
            int minLen = Math.min(s1.length(), s2.length());
            int maxLen = Math.max(s1.length());
            return 50 + (minLen * 40 / maxLen);
        }
        int prefixLen = 0;
        int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefixLen++;
            else break;
        }
        return prefixLen >= 2 ? prefixLen * 5 : 0;
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);
        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);
        if (itemDay.get(Calendar.YEAR) == todayDay.get(Calendar.DAY_OF_YEAR)) {
            return "今天";
        }
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.setTime(todayDay.getTime());
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        if (itemDay.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)) {
            return "明天";
        }
        Calendar dayAfter = Calendar.getInstance();
        dayAfter.setTime(todayDay.getTime());
        dayAfter.add(Calendar.DAY_OF_YEAR, 2);
        if (itemDay.get(Calendar.YEAR) == dayAfter.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == dayAfter.get(Calendar.DAY_OF_YEAR)) {
            return "后天";
        }
        String[] weekDays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        int dayOfWeek = itemCal.get(Calendar.DAY_OF_WEEK) - 1;
        return weekDays[dayOfWeek];
    }

    // ========== 标准完整release 规范资源释放 ==========
    public void release() {
        // 1. 清空全部EPG节目缓存集合
        if (channelEpgMap != null) {
            channelEpgMap.clear();
            channelEpgMap = null;
        }
        // 2. 销毁主线程Handler，清空所有待执行回调
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        // 3. 清空上下文弱引用
        if (ctxRef != null) {
            ctxRef.clear();
            ctxRef = null;
        }
        // 4. 置空缓存管理器、网络地址、标记变量
        cacheManager = null;
        epgUrl = null;
        hasPrintedSample = false;
        // 可选：彻底销毁单例
        instance = null;
        SettingsActivity.log("【EPG】资源全部释放完成");
    }
}
