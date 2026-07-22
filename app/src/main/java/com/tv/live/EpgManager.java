package com.tv.live;

import android.annotation.SuppressLint; // 🟢 新增导入
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tv.live.util.CacheManager;
import com.tv.live.manager.HuyaTogetherWatchManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * ✅ EPG节目单管理器（带缓存 + 智能匹配 + 内存优化版）
 */
@SuppressLint("StaticFieldLeak") // 🟢 忽略 Lint 静态字段持有 ApplicationContext 的安全警告
public class EpgManager {

    private static EpgManager instance;
    private final Map<String, List<Channel.EpgItem>> channelEpgMap = new ConcurrentHashMap<>();
    
    private String epgUrl = UrlConfig.EPG_URL;
    private boolean hasPrintedSample = false;

    private CacheManager cacheManager;
    private Context context;

    private final Map<String, String> normalizedNameCache = new ConcurrentHashMap<>();

    private static final String CACHE_KEY_EPG = "epg";
    
    private static final String[] TOGETHER_WATCH_MOVIE_KEYWORDS = {"喜剧", "动作", "惊悚", "科幻", "古装", "爱情", "冒险", "战争", "恐怖", "犯罪"};
    private static final String[] TOGETHER_WATCH_TV_KEYWORDS = {"古装", "军旅", "搞笑", "悬疑", "都市", "剧情", "家庭", "情感", "历史", "偶像"};
    private static final String[] TOGETHER_WATCH_VARIETY_KEYWORDS = {"综艺", "真人秀", "访谈", "选秀", "歌舞", "竞技"};
    private static final String[] TOGETHER_WATCH_ANIME_KEYWORDS = {"动画", "动漫", "卡通", "剧场版", "OVA"};

    private static final String FUNGOLIVE_SERVER = "http://nowtv-new.xiaoyouzb.cn";
    private static final String FUNGOLIVE_EPG_API = "/channel/get_channel_current_epg";
    private static final String FUNGOLIVE_SIGN_KEY = "fungolive";

    private final Map<String, List<Channel.EpgItem>> fungoliveEpgCache = new ConcurrentHashMap<>();

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

    private EpgManager(Context ctx) {
        this.context = ctx;
        this.cacheManager = CacheManager.getInstance(ctx);
    }

    public void setEpgUrl(String url) {
        this.epgUrl = url;
    }

    public void loadEpgFromM3u(String m3uUrl, Runnable callback) {
        new Thread(() -> {
            String extractedEpgUrl = extractEpgUrlFromM3u(m3uUrl);
            if (extractedEpgUrl != null && !extractedEpgUrl.isEmpty()) {
                epgUrl = extractedEpgUrl;
            }
            loadEpg(callback);
        }).start();
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
        } finally {
            try {
                if (reader != null) reader.close();
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
        return null;
    }

    public void loadEpg(Runnable callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;

            try {
                URL url = new URL(epgUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();
                in = conn.getInputStream();

                if (epgUrl.endsWith(".gz")) {
                    in = new GZIPInputStream(in);
                }

                long savedBytes = cacheManager.saveFileCache(CACHE_KEY_EPG, in);
                if (savedBytes <= 0) {
                    return;
                }

                hasPrintedSample = false;
                channelEpgMap.clear();

                InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
                if (cacheIs == null) {
                    return;
                }

                try {
                    parseXml(cacheIs);
                } finally {
                    cacheIs.close();
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (in != null) in.close();
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(callback);
            }
        }).start();
    }

    public boolean loadEpgFromCache() {
        try {
            InputStream cacheIs = cacheManager.getFileCacheStream(CACHE_KEY_EPG);
            if (cacheIs == null) {
                return false;
            }

            hasPrintedSample = false;
            channelEpgMap.clear();

            try {
                parseXml(cacheIs);
            } finally {
                cacheIs.close();
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================
    // 🟢【核心修复】parseXml 方法，修复了节目单被逐条覆盖导致空白的问题
    // ====================================================================
    private void parseXml(InputStream is) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xml = factory.newPullParser();
        xml.setInput(is, "UTF-8");

        // 🟢【关键修复】添加 Locale.US，解决 SimpleDateFormat 区域设置警告
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        sdf.setLenient(true);

        Calendar todayCheck = Calendar.getInstance();

        String currentChannelName = null;
        List<Channel.EpgItem> tempPrograms = new ArrayList<>();

        while (xml.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (xml.getEventType() == XmlPullParser.START_TAG) {
                String tag = xml.getName();

                if ("channel".equals(tag)) {
                    // 🟢【关键修复】检测到新频道时，先保存前一个频道的完整节目列表
                    if (currentChannelName != null && !tempPrograms.isEmpty()) {
                        channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
                        tempPrograms.clear();
                    }
                    currentChannelName = null;
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

                        String timeStr = start.substring(8, 10) + ":" + start.substring(10, 12)
                                + " - " + stop.substring(8, 10) + ":" + stop.substring(10, 12);

                        Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, "", false);
                        tempPrograms.add(item);

                    } catch (Exception e) {
                    }
                }

                if ("title".equals(tag) && !tempPrograms.isEmpty()) {
                    String title = xml.nextText().trim();
                    tempPrograms.get(tempPrograms.size() - 1).title = title;
                }
            }

            // 🟢【移除】去掉了原来 END_TAG 为 "programme" 时立即保存并清空的逻辑，
            // 避免每读一个节目就覆盖一次之前的数据。
            xml.next();
        }

        // 🟢【关键修复】解析结束后，保存最后一个频道的节目列表
        if (currentChannelName != null && !tempPrograms.isEmpty()) {
            channelEpgMap.put(currentChannelName, new ArrayList<>(tempPrograms));
        }
    }

    public List<Channel.EpgItem> getEpg(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return new ArrayList<>();
        }

        if (isTogetherWatchChannel(channelName)) {
            List<Channel.EpgItem> remoteEpg = fetchFungoliveEpg(channelName);
            if (remoteEpg != null && !remoteEpg.isEmpty()) {
                return remoteEpg;
            }
            return generateTogetherWatchEpg(channelName);
        }

        if (channelEpgMap.containsKey(channelName)) {
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
            return channelEpgMap.get(bestMatch);
        }

        return new ArrayList<>();
    }

    public List<Channel.EpgItem> getEpg(Channel channel) {
        if (channel == null) {
            return new ArrayList<>();
        }

        if (channel.isTogetherWatch()) {
            List<Channel.EpgItem> remoteEpg = fetchFungoliveEpg(channel.getName());
            if (remoteEpg != null && !remoteEpg.isEmpty()) {
                return remoteEpg;
            }
            return generateTogetherWatchEpg(channel.getName());
        }

        return getEpg(channel.getName());
    }

    private boolean isTogetherWatchChannel(String channelName) {
        if (channelName == null) return false;
        String lowerName = channelName.toLowerCase(Locale.ROOT);
        return lowerName.contains("一起看") || 
               lowerName.contains("喜剧") || 
               lowerName.contains("动作") || 
               lowerName.contains("惊悚") || 
               lowerName.contains("科幻") || 
               lowerName.contains("古装") || 
               lowerName.contains("动画") || 
               lowerName.contains("综艺") || 
               lowerName.contains("剧集") || 
               lowerName.contains("悬疑");
    }

    private List<Channel.EpgItem> generateTogetherWatchEpg(String channelName) {
        List<Channel.EpgItem> epgList = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        sdf.setLenient(true);
        
        String channelType = detectChannelType(channelName);
        
        String[][] programs = generateProgramsForType(channelType, channelName);
        
        int startHour = now.get(Calendar.HOUR_OF_DAY);
        
        for (int i = 0; i < programs.length; i++) {
            int hour = (startHour + i) % 24;
            int nextHour = (hour + 1) % 24;
            
            String timeStr = String.format("%02d:%02d - %02d:%02d", hour, 0, nextHour, 0);
            
            Calendar itemCal = Calendar.getInstance();
            itemCal.set(Calendar.HOUR_OF_DAY, hour);
            itemCal.set(Calendar.MINUTE, 0);
            itemCal.set(Calendar.SECOND, 0);
            
            if (hour < startHour) {
                itemCal.add(Calendar.DAY_OF_YEAR, 1);
            }
            
            String dayName = getDayName(itemCal, now);
            String title = programs[i][0];
            String subTitle = programs[i][1];
            
            boolean isPlaying = hour == startHour;
            
            Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, title + (subTitle.isEmpty() ? "" : " - " + subTitle), isPlaying);
            epgList.add(item);
        }
        
        return epgList;
    }

    private String detectChannelType(String channelName) {
        if (channelName == null) return "综合";
        String lowerName = channelName.toLowerCase(Locale.ROOT);
        
        for (String keyword : TOGETHER_WATCH_MOVIE_KEYWORDS) {
            if (lowerName.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "电影";
            }
        }
        
        for (String keyword : TOGETHER_WATCH_TV_KEYWORDS) {
            if (lowerName.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "剧集";
            }
        }
        
        for (String keyword : TOGETHER_WATCH_ANIME_KEYWORDS) {
            if (lowerName.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "动画";
            }
        }
        
        for (String keyword : TOGETHER_WATCH_VARIETY_KEYWORDS) {
            if (lowerName.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "综艺";
            }
        }
        
        return "综合";
    }

    private String[][] generateProgramsForType(String type, String channelName) {
        switch (type) {
            case "电影":
                return new String[][]{
                    {"经典喜剧电影", "欢乐不断"},
                    {"动作大片精选", "热血激情"},
                    {"科幻巨制", "未来世界"},
                    {"惊悚悬疑电影", "紧张刺激"},
                    {"爱情文艺片", "浪漫唯美"},
                    {"战争史诗", "震撼人心"},
                    {"恐怖惊悚", "心跳加速"},
                    {"犯罪推理", "烧脑解谜"},
                    {"动画电影", "奇幻冒险"},
                    {"纪录片", "探索世界"},
                    {"家庭温情片", "感人至深"},
                    {"冒险动作", "惊险刺激"},
                    {"古装武侠", "江湖恩怨"},
                    {"喜剧精选", "爆笑不停"},
                    {"爱情电影", "甜蜜浪漫"},
                    {"科幻经典", "视觉盛宴"},
                    {"悬疑推理", "层层揭秘"},
                    {"动作合集", "精彩不断"},
                    {"喜剧电影", "开心一刻"},
                    {"恐怖电影", "午夜惊魂"},
                    {"犯罪电影", "正邪对决"},
                    {"冒险电影", "探索未知"},
                    {"战争电影", "历史重现"},
                    {"文艺电影", "艺术享受"}
                };
            case "剧集":
                return new String[][]{
                    {"古装剧集", "历史传奇"},
                    {"都市情感剧", "现代生活"},
                    {"悬疑推理剧", "烧脑剧情"},
                    {"家庭伦理剧", "亲情故事"},
                    {"青春偶像剧", "浪漫爱情"},
                    {"军旅题材剧", "热血军营"},
                    {"谍战剧", "潜伏较量"},
                    {"年代剧", "时代变迁"},
                    {"农村题材", "乡土情怀"},
                    {"神话剧", "神仙传说"},
                    {"宫廷剧", "后宫风云"},
                    {"武侠剧", "江湖恩怨"},
                    {"商战剧", "商场博弈"},
                    {"律政剧", "法庭较量"},
                    {"医疗剧", "医者仁心"},
                    {"警匪剧", "正邪交锋"},
                    {"校园剧", "青春回忆"},
                    {"职场剧", "职场风云"},
                    {"家庭剧", "温馨生活"},
                    {"情感剧", "爱恨情仇"},
                    {"历史剧", "王朝兴衰"},
                    {"偶像剧集", "追星必看"},
                    {"剧情精选", "精彩不断"}
                };
            case "动画":
                return new String[][]{
                    {"日本动漫", "精彩不断"},
                    {"国产动画", "国漫崛起"},
                    {"欧美动画", "创意无限"},
                    {"经典动画", "童年回忆"},
                    {"剧场版", "震撼上映"},
                    {"OVA", "独家放送"},
                    {"新番动画", "最新更新"},
                    {"热血动漫", "激情燃烧"},
                    {"治愈系", "温暖人心"},
                    {"悬疑动画", "烧脑解谜"},
                    {"搞笑动画", "欢乐无限"},
                    {"恋爱动画", "甜蜜浪漫"},
                    {"科幻动画", "未来世界"},
                    {"奇幻动画", "魔法世界"},
                    {"冒险动画", "探索未知"},
                    {"竞技动画", "热血拼搏"},
                    {"校园动画", "青春校园"},
                    {"推理动画", "真相只有一个"},
                    {"恐怖动画", "惊悚刺激"},
                    {"萌系动画", "可爱治愈"},
                    {"音乐动画", "旋律优美"},
                    {"运动动画", "挥洒汗水"},
                    {"动画精选", "精彩合集"}
                };
            case "综艺":
                return new String[][]{
                    {"真人秀", "明星百态"},
                    {"脱口秀", "幽默风趣"},
                    {"歌唱比赛", "天籁之音"},
                    {"舞蹈竞技", "舞姿翩翩"},
                    {"游戏综艺", "欢乐互动"},
                    {"访谈节目", "明星专访"},
                    {"美食节目", "舌尖诱惑"},
                    {"旅行综艺", "探索世界"},
                    {"亲子节目", "温馨时刻"},
                    {"喜剧节目", "爆笑连连"},
                    {"选秀节目", "梦想起航"},
                    {"竞技综艺", "热血拼搏"},
                    {"情感节目", "真情实感"},
                    {"文化综艺", "传承经典"},
                    {"体育综艺", "运动激情"},
                    {"音乐综艺", "视听盛宴"},
                    {"才艺展示", "各显神通"},
                    {"户外综艺", "亲近自然"},
                    {"室内综艺", "欢乐时光"},
                    {"晚会盛典", "星光熠熠"},
                    {"颁奖典礼", "荣耀时刻"},
                    {"演唱会", "现场直击"},
                    {"综艺精选", "精彩不断"}
                };
            default:
                return new String[][]{
                    {"精彩节目", "正在热播"},
                    {"热门内容", "不容错过"},
                    {"精选推荐", "精彩呈现"},
                    {"直播现场", "实时互动"},
                    {"精彩回放", "重温经典"},
                    {"特别节目", "独家放送"},
                    {"精彩继续", "敬请期待"},
                    {"热门推荐", "人气爆棚"},
                    {"精品内容", "品质保证"},
                    {"独家放送", "抢先观看"},
                    {"精彩直播", "实时互动"},
                    {"精选内容", "不容错过"},
                    {"热门节目", "人气高涨"},
                    {"精彩不断", "持续热播"},
                    {"特别策划", "独家呈现"},
                    {"精彩瞬间", "值得珍藏"},
                    {"热门精选", "精彩呈现"},
                    {"直播精选", "实时精彩"},
                    {"精品推荐", "品质之选"},
                    {"精彩内容", "持续更新"},
                    {"热门直播", "人气爆棚"},
                    {"精选直播", "精彩不断"},
                    {"精彩节目", "持续热播"},
                    {"热门内容", "精彩呈现"}
                };
        }
    }

    private String normalizeChannelName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (normalizedNameCache.containsKey(name)) {
            return normalizedNameCache.get(name);
        }

        // 🟢【关键修复】添加 Locale.ROOT，解决 toLowerCase 区域设置警告
        String result = name.toLowerCase(Locale.ROOT);

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

        normalizedNameCache.put(name, result);
        return result;
    }

    private int calculateMatchScore(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }

        if (s1.equals(s2)) {
            return 100;
        }

        if (s1.contains(s2) || s2.contains(s1)) {
            int minLen = Math.min(s1.length(), s2.length());
            int maxLen = Math.max(s1.length(), s2.length());
            return 50 + (minLen * 40 / maxLen);
        }

        int prefixLen = 0;
        int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefixLen++;
            } else {
                break;
            }
        }
        if (prefixLen >= 2) {
            return prefixLen * 5;
        }

        return 0;
    }

    public String getDayName(Calendar itemCal, Calendar todayCal) {
        Calendar itemDay = Calendar.getInstance();
        itemDay.setTime(itemCal.getTime());
        itemDay.set(Calendar.HOUR_OF_DAY, 0);
        itemDay.set(Calendar.MINUTE, 0);
        itemDay.set(Calendar.SECOND, 0);
        itemDay.set(Calendar.MILLISECOND, 0);

        Calendar todayDay = Calendar.getInstance();
        todayDay.setTime(todayCal.getTime());
        todayDay.set(Calendar.HOUR_OF_DAY, 0);
        todayDay.set(Calendar.MINUTE, 0);
        todayDay.set(Calendar.SECOND, 0);
        todayDay.set(Calendar.MILLISECOND, 0);

        if (itemDay.get(Calendar.YEAR) == todayDay.get(Calendar.YEAR)
                && itemDay.get(Calendar.DAY_OF_YEAR) == todayDay.get(Calendar.DAY_OF_YEAR)) {
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

    public int getChannelEpgMapSize() {
        return channelEpgMap.size();
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private List<Channel.EpgItem> fetchFungoliveEpg(String channelName) {
        if (fungoliveEpgCache.containsKey(channelName)) {
            return fungoliveEpgCache.get(channelName);
        }

        List<Channel.EpgItem> epgList = fetchHuyaChannelEpg(channelName);
        if (epgList != null && !epgList.isEmpty()) {
            Log.d("HuyaEpg", "【虎牙EPG】获取成功，节目数=" + epgList.size());
            fungoliveEpgCache.put(channelName, epgList);
            return epgList;
        }

        return null;
    }

    private List<Channel.EpgItem> fetchHuyaChannelEpg(String channelName) {
        try {
            Log.d("HuyaEpg", "【虎牙EPG】开始从虎牙频道列表获取，channel=" + channelName);

            String category = detectChannelType(channelName);
            int subCategoryId = getSubCategoryId(category);
            
            if (subCategoryId <= 0) {
                Log.d("HuyaEpg", "【虎牙EPG】无法识别频道类型，category=" + category);
                return null;
            }

            String apiUrl = "https://live.cdn.huya.com/liveHttpUI/getTmpLiveList" +
                    "?iGid=2135&iTmpId=" + subCategoryId + "&iPageNo=1&iPageSize=24";

            Log.d("HuyaEpg", "【虎牙EPG】请求URL=" + apiUrl);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.addRequestProperty("Referer", "https://www.huya.com/");

            int responseCode = conn.getResponseCode();
            Log.d("HuyaEpg", "【虎牙EPG】响应码=" + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String response = sb.toString();
                Log.d("HuyaEpg", "【虎牙EPG】响应长度=" + response.length());

                return parseHuyaChannelEpgResponse(response, channelName);
            } else {
                Log.d("HuyaEpg", "【虎牙EPG】请求失败，响应码=" + responseCode);
            }
        } catch (Exception e) {
            Log.d("HuyaEpg", "【虎牙EPG】请求异常: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private int getSubCategoryId(String channelType) {
        switch (channelType) {
            case "电影": return 2067;
            case "剧集": return 2079;
            case "动画": return 6861;
            case "综艺": return 1011;
            default: return 2067;
        }
    }

    private List<Channel.EpgItem> parseHuyaChannelEpgResponse(String response, String currentChannelName) {
        try {
            JSONObject json = new JSONObject(response);
            JSONArray vList = json.optJSONArray("vList");
            if (vList == null || vList.length() == 0) {
                Log.d("HuyaEpg", "【虎牙EPG】vList为空");
                return null;
            }

            List<Channel.EpgItem> epgList = new ArrayList<>();
            Calendar now = Calendar.getInstance();
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", java.util.Locale.US);

            int startHour = now.get(Calendar.HOUR_OF_DAY);
            boolean foundCurrentChannel = false;

            for (int i = 0; i < Math.min(vList.length(), 24); i++) {
                JSONObject room = vList.getJSONObject(i);
                
                String roomName = room.optString("sRoomName", "");
                String introduction = room.optString("sIntroduction", "");
                String displayTitle = TextUtils.isEmpty(roomName) ? introduction : roomName;
                
                if (TextUtils.isEmpty(displayTitle)) continue;

                int hour = (startHour + i) % 24;
                int nextHour = (hour + 1) % 24;
                
                String timeStr = String.format("%02d:%02d - %02d:%02d", hour, 0, nextHour, 0);
                
                Calendar itemCal = Calendar.getInstance();
                itemCal.set(Calendar.HOUR_OF_DAY, hour);
                itemCal.set(Calendar.MINUTE, 0);
                itemCal.set(Calendar.SECOND, 0);
                
                if (hour < startHour) {
                    itemCal.add(Calendar.DAY_OF_YEAR, 1);
                }
                
                String dayName = getDayName(itemCal, now);
                
                boolean isPlaying = (!foundCurrentChannel && hour == startHour) ||
                        displayTitle.equalsIgnoreCase(currentChannelName) ||
                        (currentChannelName.contains(displayTitle) || displayTitle.contains(currentChannelName));
                
                if (isPlaying) {
                    foundCurrentChannel = true;
                }
                
                Channel.EpgItem item = new Channel.EpgItem(dayName, timeStr, displayTitle, isPlaying);
                epgList.add(item);
                
                Log.d("HuyaEpg", "【虎牙EPG】节目" + (i+1) + ": " + timeStr + " - " + displayTitle + (isPlaying ? " (播放中)" : ""));
            }

            return epgList;
        } catch (Exception e) {
            Log.d("HuyaEpg", "【虎牙EPG】解析异常: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private List<Channel.EpgItem> parseFungoliveEpgResponse(String response, String channelName) {
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");

                List<Channel.EpgItem> epgList = new ArrayList<>();
                Calendar now = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);

                if (data.has("current")) {
                    JSONObject current = data.getJSONObject("current");
                    String title = current.optString("title", "");
                    String startTime = current.optString("startTime", "");
                    String endTime = current.optString("endTime", "");

                    if (!title.isEmpty()) {
                        String timeStr = startTime + " - " + endTime;
                        Channel.EpgItem item = new Channel.EpgItem("今天", timeStr, title, true);
                        epgList.add(item);
                    }
                }

                if (data.has("next")) {
                    JSONObject next = data.getJSONObject("next");
                    String title = next.optString("title", "");
                    String startTime = next.optString("startTime", "");
                    String endTime = next.optString("endTime", "");

                    if (!title.isEmpty()) {
                        String timeStr = startTime + " - " + endTime;
                        Channel.EpgItem item = new Channel.EpgItem("今天", timeStr, title, false);
                        epgList.add(item);
                    }
                }

                if (data.has("list")) {
                    JSONArray list = data.getJSONArray("list");
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject itemObj = list.getJSONObject(i);
                        String title = itemObj.optString("title", "");
                        String startTime = itemObj.optString("startTime", "");
                        String endTime = itemObj.optString("endTime", "");

                        if (!title.isEmpty()) {
                            String timeStr = startTime + " - " + endTime;
                            boolean isPlaying = itemObj.optBoolean("isPlaying", false);
                            Channel.EpgItem item = new Channel.EpgItem("今天", timeStr, title, isPlaying);
                            epgList.add(item);
                        }
                    }
                }

                return epgList;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
