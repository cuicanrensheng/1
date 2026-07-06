package com.tv.live;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors; // 🟢 修复1：导入 Executors 而不是 Exec
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaylistParser {
    // 全局常量
    public static final long URL_STATUS_INVALID = -1L;
    public static final long URL_STATUS_UNTEST = Long.MAX_VALUE;
    public static int TEST_CONNECT_TIMEOUT = 2000;
    public static int TEST_READ_TIMEOUT = 1500;
    public static int TEST_RETRY_COUNT = 1;
    public static int ASYNC_TEST_BATCH_SIZE = 20;
    private static final ExecutorService ASYNC_POOL = Executors.newFixedThreadPool(8);

    // 正则
    private static final Pattern HTTP_PATTERN = Pattern.compile("^(http|https)://.+");
    private static final Pattern RTSP_PATTERN = Pattern.compile("^rtsp://.+");
    private static final Pattern RTMP_PATTERN = Pattern.compile("^rtmp://.+");
    private static final Pattern TXT_LINE_PATTERN = Pattern.compile("^([^,]+),(http|rtsp|rtmp)://.+");

    // 单例
    private static final PlaylistParser INSTANCE = new PlaylistParser();
    // 分组合并规则 map<原始分组,目标合并分组>
    private final Map<String, String> groupMergeRuleMap = new HashMap<>();
    // EPG频道名称模糊匹配库
    private final Map<String, String> epgNameMap = new HashMap<>();
    // 解析缓存 key=url/文件路径 value=解析结果
    private final Map<String, List<Channel>> parseCache = new HashMap<>();
    // 测速持久缓存
    private final Map<String, Long> speedCacheStore = new HashMap<>();
    // 日志回调
    public interface LogCallback {
        void info(String msg);
        void error(String msg, Exception e);
    }
    private LogCallback logCallback;

    // 线路优先级配置内部类
    public static class UrlPriorityConfig {
        private final Map<Pattern, Integer> domainRules = new LinkedHashMap<>();
        private final Map<String, Integer> protoWeight = new HashMap<>();
        public boolean preferLan = true;
        public int speedFactor = 1;
        private final Map<String, Integer> forceUrl = new HashMap<>();

        public UrlPriorityConfig() {
            protoWeight.put("http", 10);
            protoWeight.put("https", 10);
            protoWeight.put("rtsp", 8);
            protoWeight.put("rtmp", 5);
        }

        public UrlPriorityConfig addDomainRule(String reg, int w) {
            domainRules.put(Pattern.compile(reg, Pattern.CASE_INSENSITIVE), w);
            return this;
        }

        public UrlPriorityConfig forceUrlWeight(String url, int w) {
            if (url != null) forceUrl.put(url.trim(), w);
            return this;
        }

        public int calcScore(String url, long speedMs) {
            if (forceUrl.containsKey(url)) return forceUrl.get(url);
            int score = 0;
            if (preferLan) {
                if (url.startsWith("http://192.") || url.startsWith("http://10.") || url.startsWith("http://172.")) score += 50;
                if (url.startsWith("rtsp://192.") || url.startsWith("rtsp://172.")) score += 50;
            }
            for (Map.Entry<Pattern, Integer> e : domainRules.entrySet()) {
                if (e.getKey().matcher(url).find()) {
                    score += e.getValue();
                    break;
                }
            }
            String proto = url.startsWith("http") ? "http" : url.startsWith("rtsp") ? "rtsp" : "rtmp";
            score += protoWeight.getOrDefault(proto, 0);
            if (speedMs != URL_STATUS_INVALID && speedMs != URL_STATUS_UNTEST) {
                score += (1000 - Math.min((int) speedMs, 1000)) * speedFactor / 100;
            }
            return score;
        }
    }

    private UrlPriorityConfig priorityConfig = new UrlPriorityConfig();

    public static PlaylistParser getInstance() {
        return INSTANCE;
    }

    // 日志设置
    public void setLogCallback(LogCallback cb) {
        this.logCallback = cb;
    }

    private void logInfo(String msg) {
        if (logCallback != null) logCallback.info(msg);
    }

    private void logErr(String msg, Exception e) {
        if (logCallback != null) logCallback.error(msg, e);
    }

    // ===================== 分组合并API =====================
    public PlaylistParser addGroupMergeRule(String srcGroup, String targetGroup) {
        if (srcGroup != null && targetGroup != null) {
            groupMergeRuleMap.put(srcGroup.trim(), targetGroup.trim());
        }
        return this;
    }

    public PlaylistParser clearGroupMergeRules() {
        groupMergeRuleMap.clear();
        return this;
    }

    // 根据原始分组获取合并后标准分组
    private String getMergedGroup(String raw) {
        if (raw == null || raw.isBlank()) return "未分类";
        return groupMergeRuleMap.getOrDefault(raw.trim(), raw.trim());
    }

    // EPG匹配库
    public PlaylistParser setEpgMatchMap(Map<String, String> map) {
        epgNameMap.clear();
        if (map != null) epgNameMap.putAll(map);
        return this;
    }

    // 解析缓存操作
    public void clearParseCache() {
        parseCache.clear();
    }

    // 测速持久缓存
    public void loadSpeedCache(Map<String, Long> diskMap) {
        if (diskMap != null) speedCacheStore.putAll(diskMap);
    }

    public Map<String, Long> exportSpeedCache() {
        return new HashMap<>(speedCacheStore);
    }

    // ===================== 测速底层 =====================
    public static long testSingleUrl(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) return URL_STATUS_INVALID;
        String key = urlStr.trim();
        if (INSTANCE.speedCacheStore.containsKey(key)) {
            long cache = INSTANCE.speedCacheStore.get(key);
            if (cache != URL_STATUS_UNTEST) return cache;
        }
        int retry = 0;
        long res = URL_STATUS_INVALID;
        while (retry <= TEST_RETRY_COUNT) {
            try {
                if (HTTP_PATTERN.matcher(urlStr).matches()) res = testHttp(urlStr);
                else if (RTSP_PATTERN.matcher(urlStr).matches()) res = testRtsp(urlStr);
                else if (RTMP_PATTERN.matcher(urlStr).matches()) res = testRtmp(urlStr);
                if (res != URL_STATUS_INVALID) break;
            } catch (Exception e) {
                INSTANCE.logErr("测速失败:" + urlStr, e);
                res = URL_STATUS_INVALID;
            }
            retry++;
        }
        INSTANCE.speedCacheStore.put(key, res);
        return res;
    }

    private static long testHttp(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            long start = System.currentTimeMillis();
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TEST_CONNECT_TIMEOUT);
            conn.setReadTimeout(TEST_READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "IPTV");
            conn.setRequestProperty("Range", "bytes=0-1024");
            int code = conn.getResponseCode();
            long cost = System.currentTimeMillis() - start;
            return code >= 200 && code < 400 ? cost : URL_STATUS_INVALID;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static long testRtsp(String urlStr) throws Exception {
        String hp = urlStr.replace("rtsp://", "").split("/")[0];
        String host = hp.split(":")[0];
        int port = hp.contains(":") ? Integer.parseInt(hp.split(":")[1]) : 554;
        long start = System.currentTimeMillis();
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), TEST_CONNECT_TIMEOUT);
        long cost = System.currentTimeMillis() - start;
        sock.close();
        return cost;
    }

    private static long testRtmp(String urlStr) throws Exception {
        String hp = urlStr.replace("rtmp://", "").split("/")[0];
        String host = hp.split(":")[0];
        int port = hp.contains(":") ? Integer.parseInt(hp.split(":")[1]) : 1935;
        long start = System.currentTimeMillis();
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), TEST_CONNECT_TIMEOUT);
        long cost = System.currentTimeMillis() - start;
        sock.close();
        return cost;
    }

    // 测速结果载体
    public static class TestResult {
        public int totalTestCount;
        public int invalidCount;
        public int getValid() {
            return totalTestCount - invalidCount;
        }
    }

    // 同步批量测速
    public TestResult testAllSync(List<Channel> list) {
        logInfo("开始同步测速");
        TestResult tr = new TestResult();
        Set<String> allUrls = new HashSet<>();
        list.forEach(c -> allUrls.addAll(c.getUrls())); // 🟢 修复变量名 all -> allUrls
        tr.totalTestCount = allUrls.size();
        Map<String, Long> speedMap = new HashMap<>();
        for (String u : allUrls) {
            long s = testSingleUrl(u);
            speedMap.put(u, s);
            if (s == URL_STATUS_INVALID) tr.invalidCount++;
        }
        for (Channel c : list) {
            for (String u : c.getUrls()) c.setUrlSpeed(u, speedMap.get(u));
            c.removeInvalidUrls();
        }
        list.removeIf(ch -> ch.getValidUrlCount() <= 0);
        logInfo("测速完成，有效线路：" + tr.getValid());
        return tr;
    }

    // 异步分片测速
    public CompletableFuture<TestResult> testAllAsync(List<Channel> list) {
        logInfo("开启异步测速任务");
        Set<String> allUrls = new HashSet<>();
        list.forEach(c -> allUrls.addAll(c.getUrls()));
        List<String> urlList = new ArrayList<>(allUrls);
        List<List<String>> batches = splitList(urlList, ASYNC_TEST_BATCH_SIZE);
        List<CompletableFuture<Map<String, Long>>> tasks = new ArrayList<>();
        for (List<String> batch : batches) {
            CompletableFuture<Map<String, Long>> task = CompletableFuture.supplyAsync(() -> {
                Map<String, Long> m = new HashMap<>();
                batch.forEach(u -> m.put(u, testSingleUrl(u)));
                return m;
            }, ASYNC_POOL);
            tasks.add(task);
        }
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenApply(v -> {
            TestResult res = new TestResult();
            res.totalTestCount = urlList.size();
            Map<String, Long> totalSpeed = new HashMap<>();
            tasks.forEach(f -> totalSpeed.putAll(f.join()));
            res.invalidCount = (int) totalSpeed.values().stream().filter(l -> l == URL_STATUS_INVALID).count();
            for (Channel ch : list) {
                for (String u : ch.getUrls()) ch.setUrlSpeed(u, totalSpeed.get(u));
                ch.removeInvalidUrls();
            }
            list.removeIf(c -> c.getValidUrlCount() <= 0);
            return res;
        });
    }

    private List<List<String>> splitList(List<String> src, int size) {
        List<List<String>> res = new ArrayList<>();
        for (int i = 0; i < src.size(); i += size) {
            int end = Math.min(i + size, src.size());
            res.add(src.subList(i, end));
        }
        return res;
    }

    // ===================== 本地文件解析（M3U/TXT自动识别） =====================
    public List<Channel> parseLocalFile(File file) throws Exception {
        String cacheKey = file.getAbsolutePath();
        if (parseCache.containsKey(cacheKey)) {
            logInfo("读取文件缓存");
            return new ArrayList<>(parseCache.get(cacheKey));
        }
        if (!file.exists() || !file.isFile()) throw new Exception("文件不存在");
        logInfo("解析本地文件：" + cacheKey);
        BufferedReader br = null;
        String firstLine;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            firstLine = br.readLine();
            br.close();
            br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            List<Channel> result;
            if (firstLine != null && firstLine.trim().startsWith("#EXTM3U")) {
                result = parseM3uReader(br);
            } else {
                result = parseTxtReader(br);
            }
            parseCache.put(cacheKey, new ArrayList<>(result));
            return result;
        } finally {
            if (br != null) br.close();
        }
    }

    // TXT简易源 名称,url
    private List<Channel> parseTxtReader(BufferedReader br) throws Exception {
        Map<String, Channel> map = new HashMap<>();
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = TXT_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                String rawName = m.group(1);
                String url = m.group(2);
                String stdName = Channel.cleanChannelName(rawName);
                String targetGroup = getMergedGroup("未分类");
                String mergeKey = stdName;
                Channel exist = map.get(mergeKey);
                if (exist != null) {
                    exist.addUrl(url);
                } else {
                    Channel ch = new Channel(rawName, url, targetGroup, "");
                    ch.setStandardGroup(targetGroup);
                    map.put(mergeKey, ch);
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    // M3U读取通用逻辑（抽离复用）
    private List<Channel> parseM3uReader(BufferedReader br) throws Exception {
        Map<String, Channel> channelMap = new HashMap<>();
        String line;
        String currentGroupRaw = "未分类";
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) continue;
            if (line.startsWith("#EXTGRP:")) {
                currentGroupRaw = line.substring(8).trim();
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                String name = "";
                String channelId = "";
                String rawGroup = currentGroupRaw;
                // 提取tvg-id
                if (line.contains("tvg-id=\"")) {
                    try {
                        channelId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取group-title
                if (line.contains("group-title=\"")) {
                    try {
                        rawGroup = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取频道名
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }
                // 分组合并转换
                String finalGroup = getMergedGroup(rawGroup);
                String uri = br.readLine();
                if (uri == null) continue;
                uri = uri.trim();
                boolean valid = HTTP_PATTERN.matcher(uri).matches() || RTSP_PATTERN.matcher(uri).matches() || RTMP_PATTERN.matcher(uri).matches();
                if (!valid) continue;
                // 合并key：优先channelId，无则标准频道名
                String stdName = Channel.cleanChannelName(name);
                String mergeKey = !channelId.isBlank() ? channelId : stdName;
                if (mergeKey.isBlank()) continue;
                Channel exist = channelMap.get(mergeKey);
                if (exist != null) {
                    exist.addUrl(uri);
                } else {
                    Channel newCh = new Channel(name, uri, rawGroup, channelId);
                    newCh.setStandardGroup(finalGroup);
                    channelMap.put(mergeKey, newCh);
                }
            }
        }
        return new ArrayList<>(channelMap.values());
    }

    // ===================== 原始单网络M3U解析 =====================
    public static List<Channel> parse(String url) throws Exception {
        String cacheKey = url.trim();
        if (INSTANCE.parseCache.containsKey(cacheKey)) {
            INSTANCE.logInfo("命中网络源缓存");
            return new ArrayList<>(INSTANCE.parseCache.get(cacheKey));
        }
        INSTANCE.logInfo("开始下载解析：" + url);
        Map<String, Channel> channelMap = new HashMap<>();
        BufferedReader br = null;
        try {
            URL sourceUrl = new URL(url);
            br = new BufferedReader(new InputStreamReader(sourceUrl.openStream(), StandardCharsets.UTF_8));
            List<Channel> list = INSTANCE.parseM3uReader(br);
            INSTANCE.parseCache.put(cacheKey, new ArrayList<>(list));
            return list;
        } finally {
            if (br != null) br.close();
        }
    }

    // ===================== 多源合并解析 =====================
    // 严格模式：任一源报错直接抛出
    public static List<Channel> parseMultiSource(List<String> sourceUrls) throws Exception {
        Map<String, Channel> totalMap = new HashMap<>();
        for (String url : sourceUrls) {
            if (url == null || url.isBlank()) continue;
            List<Channel> single = parse(url.trim());
            mergeSingleChannels(single, totalMap);
        }
        return new ArrayList<>(totalMap.values());
    }

    // 安全容错模式：单个源失败跳过不中断
    public static List<Channel> parseMultiSourceSafe(List<String> sourceUrls) {
        Map<String, Channel> totalMap = new HashMap<>();
        if (sourceUrls == null || sourceUrls.isEmpty()) return new ArrayList<>();
        for (String url : sourceUrls) {
            if (url == null || url.isBlank()) continue;
            List<Channel> single;
            try {
                single = parse(url.trim());
            } catch (Exception e) {
                INSTANCE.logErr("解析源失败：" + url, e);
                continue;
            }
            mergeSingleChannels(single, totalMap);
        }
        return new ArrayList<>(totalMap.values());
    }

    // 合并单批频道进入全局Map（核心合并逻辑）
    private static void mergeSingleChannels(List<Channel> singleList, Map<String, Channel> totalMap) {
        for (Channel ch : singleList) {
            String mergeKey = !ch.getChannelId().isBlank() ? ch.getChannelId() : ch.getStandardName();
            if (mergeKey.isBlank()) continue;
            Channel exist = totalMap.get(mergeKey);
            if (exist != null) {
                exist.mergeChannel(ch);
            } else {
                totalMap.put(mergeKey, ch);
            }
        }
    }

    // ===================== 频道排序枚举与工具 =====================
    public enum SortType {
        GROUP_ASC, NAME_ASC, URL_COUNT_DESC
    }

    // 🟢 修复2：修正方法参数类型为 SortType
    public List<Channel> sortChannels(List<Channel> raw, SortType type) {
        List<Channel> list = new ArrayList<>(raw);
        switch (type) {
            case GROUP_ASC:
                list.sort((a, b) -> a.getStandardGroup().compareTo(b.getStandardGroup()));
                break;
            case NAME_ASC:
                list.sort((a, b) -> a.getStandardName().compareTo(b.getStandardName()));
                break;
            case URL_COUNT_DESC:
                list.sort((a, b) -> Integer.compare(b.getValidUrlCount(), a.getValidUrlCount()));
                break;
        }
        return list;
    }

    // ===================== M3U导出 =====================
    public String exportM3u(List<Channel> channelList) {
        UrlPriorityConfig cfg = this.priorityConfig;
        Map<String, List<Channel>> groupMap = new LinkedHashMap<>();
        for (Channel ch : channelList) {
            groupMap.computeIfAbsent(ch.getStandardGroup(), k -> new ArrayList<>()).add(ch);
        }
        // 清理空分组
        Map<String, List<Channel>> cleanGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<Channel>> entry : groupMap.entrySet()) {
            if (!entry.getKey().isBlank() && !entry.getValue().isEmpty()) {
                cleanGroup.put(entry.getKey(), entry.getValue());
            }
        }
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (Map.Entry<String, List<Channel>> entry : cleanGroup.entrySet()) {
            String g = entry.getKey();
            sb.append("#EXTGRP:").append(g).append("\n");
            for (Channel ch : entry.getValue()) {
                List<String> urls = ch.getUrls();
                // 按优先级排序
                urls.sort((u1, u2) -> {
                    long s1 = ch.getUrlSpeed(u1);
                    long s2 = ch.getUrlSpeed(u2);
                    boolean inv1 = s1 == URL_STATUS_INVALID;
                    boolean inv2 = s2 == URL_STATUS_INVALID;
                    if (inv1 && !inv2) return 1;
                    if (!inv1 && inv2) return -1;
                    if (inv1 && inv2) return 0;
                    return Integer.compare(cfg.calcScore(u2, s2), cfg.calcScore(u1, s1));
                });
                for (String url : urls) {
                    if (ch.getUrlSpeed(url) == URL_STATUS_INVALID) continue;
                    sb.append("#EXTINF:-1 tvg-id=\"").append(ch.getChannelId())
                            .append("\" group-title=\"").append(g)
                            .append(",").append(ch.getName()).append("\n");
                    sb.append(url).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ===================== 流式结果包装类 =====================
    public static class ParseResult {
        private final List<Channel> channels;
        private final PlaylistParser parser;

        public ParseResult(List<Channel> list, PlaylistParser parser) {
            this.channels = list;
            this.parser = parser;
        }

        public List<Channel> getChannels() {
            return channels;
        }

        // 配置线路优先级
        public ParseResult priority(Consumer<UrlPriorityConfig> consumer) {
            consumer.accept(parser.priorityConfig);
            return this;
        }

        // 添加分组合并规则
        public ParseResult mergeGroup(String src, String target) {
            parser.addGroupMergeRule(src, target);
            return this;
        }

        // 设置EPG匹配表
        public ParseResult epgMap(Map<String, String> map) {
            parser.setEpgMatchMap(map);
            return this;
        }

        // 频道排序
        public ParseResult sort(SortType type) {
            List<Channel> sorted = parser.sortChannels(channels, type);
            return new ParseResult(sorted, parser);
        }

        // 同步测速
        public TestResult testSync() {
            return parser.testAllSync(channels);
        }

        // 异步测速
        public CompletableFuture<ParseResult> testAsync() {
            return parser.testAllAsync(channels).thenApply(tr -> this);
        }

        // 导出M3U文本
        public String export() {
            return parser.exportM3u(channels);
        }
    }

    // 静态快捷入口
    public static ParseResult parseSingle(String url) throws Exception {
        return new ParseResult(parse(url), getInstance());
    }

    public static ParseResult parseMerge(List<String> urls) throws Exception {
        return new ParseResult(parseMultiSource(urls), getInstance());
    }

    public static ParseResult parseMergeSafe(List<String> urls) {
        return new ParseResult(parseMultiSourceSafe(urls), getInstance());
    }

    public static ParseResult parseFile(File file) throws Exception {
        return new ParseResult(getInstance().parseLocalFile(file), getInstance());
    }

    // 释放线程池
    public static void shutdown() {
        ASYNC_POOL.shutdown();
    }
}
