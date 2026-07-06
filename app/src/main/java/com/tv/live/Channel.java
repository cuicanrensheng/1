package com.tv.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Channel {
    private String name;
    private String standardName; // 清洗后标准频道名
    private String group;
    private String standardGroup; // 分组合并后分组名
    private String channelId; // 原tvg-id对应字段
    // 所有备用线路
    private List<String> urls = new ArrayList<>();
    // 测速缓存 key=url  value=耗时ms，-1失效，MAX未测速
    private final Map<String, Long> speedCache = new HashMap<>();
    // 当前选中线路索引
    private int currentLineIndex = 0;

    public static final long URL_STATUS_INVALID = -1L;
    public static final long URL_STATUS_UNTEST = Long.MAX_VALUE;

    // 存储当前频道EPG列表
    private List<EpgItem> epgList = new ArrayList<>();

    public Channel(String name, String playUrl, String group, String channelId) {
        this.name = name;
        this.standardName = cleanChannelName(name);
        this.group = group;
        this.standardGroup = group;
        this.channelId = channelId;
        // 🟢 修复1：将 play 改为 playUrl，并改用更安全的 isEmpty 判断
        if (playUrl != null && !playUrl.trim().isEmpty()) {
            urls.add(playUrl.trim());
            speedCache.putIfAbsent(playUrl.trim(), URL_STATUS_UNTEST);
        }
    }

    // 频道名称标准化清洗（去除高清/4K/HD/符号后缀）
    public static String cleanChannelName(String raw) {
        if (raw == null || raw.isBlank()) return "未知频道";
        String temp = raw.trim()
                .replaceAll("[★☆◆◇#@&*]", "")
                .replaceAll("[-_ ](高清|超清|蓝光|标清|4K|8K|HD|SD|VIP|付费|直播|线路|源)", "");
        return temp.trim();
    }

    // ===================== 基础get/set =====================
    public String getName() {
        return name;
    }

    public String getStandardName() {
        return standardName;
    }

    public void setName(String name) {
        this.name = name;
        this.standardName = cleanChannelName(name);
    }

    public String getGroup() {
        return group;
    }

    public String getStandardGroup() {
        return standardGroup;
    }

    public void setGroup(String group) {
        this.group = group;
        this.standardGroup = group;
    }

    // 设置合并后的标准分组（分组合并专用）
    public void setStandardGroup(String standardGroup) {
        this.standardGroup = standardGroup;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    // ===================== 线路相关 =====================
    public String getPlayUrl() {
        if (urls == null || urls.isEmpty()) return null;
        if (currentLineIndex >= urls.size()) {
            currentLineIndex = 0;
        }
        return urls.get(currentLineIndex);
    }

    public List<String> getUrls() {
        return new ArrayList<>(urls);
    }

    public void setUrls(List<String> urls) {
        this.urls = new ArrayList<>();
        this.speedCache.clear();
        for (String u : urls) {
            addUrl(u);
        }
    }

    // 添加线路，自动去重+测速标记
    public void addUrl(String url) {
        if (url == null || url.isBlank()) return;
        String real = url.trim();
        if (!urls.contains(real)) {
            urls.add(real);
            speedCache.putIfAbsent(real, URL_STATUS_UNTEST);
        }
    }

    // 合并另一个频道所有线路（多源合并核心）
    public void mergeChannel(Channel other) {
        if (other == null) return;
        for (String u : other.getUrls()) {
            addUrl(u);
        }
    }

    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    public void setCurrentLineIndex(int index) {
        if (index >= 0 && index < urls.size()) {
            this.currentLineIndex = index;
        }
    }

    // 获取有效线路数量（排除失效）
    public int getValidUrlCount() {
        int count = 0;
        for (String u : urls) {
            if (speedCache.getOrDefault(u, URL_STATUS_UNTEST) != URL_STATUS_INVALID) {
                count++;
            }
        }
        return count;
    }

    // ===================== 测速缓存 =====================
    public void setUrlSpeed(String url, long costMs) {
        if (url != null) speedCache.put(url.trim(), costMs);
    }

    public long getUrlSpeed(String url) {
        if (url == null) return URL_STATUS_UNTEST;
        return speedCache.getOrDefault(url.trim(), URL_STATUS_UNTEST);
    }

    // 删除所有失效线路，返回删除条数
    public int removeInvalidUrls() {
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, Long> entry : speedCache.entrySet()) {
            if (entry.getValue() == URL_STATUS_INVALID) {
                invalid.add(entry.getKey());
            }
        }
        for (String u : invalid) {
            urls.remove(u);
            speedCache.remove(u);
        }
        return invalid.size();
    }

    // ===================== EPG节目单内部类 =====================
    public static class EpgItem {
        public String dayName;
        public String time;
        public String title;
        public boolean isPlaying;

        public EpgItem(String dayName, String time, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.title = title;
            this.isPlaying = isPlaying;
        }

        public String getReplayUrl() {
            return null;
        }
    }

    public List<EpgItem> getEpgList() {
        return new ArrayList<>(epgList);
    }

    // 🟢 修复2：将 this.epg 改为 this.epgList
    public void setEpgList(List<EpgItem> epgList) {
        this.epgList = epgList == null ? new ArrayList<>() : new ArrayList<>(epgList);
    }

    public void addEpgItem(EpgItem item) {
        if (item != null) epgList.add(item);
    }
}
