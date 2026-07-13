package com.tv.live;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    private String name;
    // 主播放地址
    private String mainPlayUrl;
    // 备用播放地址列表
    private List<String> backupUrls;
    private String group;
    private String channelId;

    // 🟢【新增】记录当前选中的线路索引 (0=主源, 1及以上=备用源)
    private int currentLineIndex = 0;

    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
    }

    // 添加备用源，自动去重
    public void addBackupUrl(String url) {
        if (url != null && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // ====== 【核心修改】根据选中的线路索引返回对应的播放地址 ======
    public String getPlayUrl() {
        // 如果选中了备用源，且备用源列表有对应索引，则返回备用源
        if (currentLineIndex > 0 && currentLineIndex - 1 < backupUrls.size()) {
            return backupUrls.get(currentLineIndex - 1);
        }
        // 否则默认返回主源
        return mainPlayUrl;
    }

    // 🟢【新增】设置当前线路索引（供设置页或切换线路逻辑调用）
    public void setCurrentLineIndex(int index) {
        this.currentLineIndex = index;
    }

    // 🟢【新增】获取当前线路索引
    public int getCurrentLineIndex() {
        return currentLineIndex;
    }

    // 新接口：获取主播放地址（备用逻辑可能需要用到）
    public String getMainPlayUrl() {
        return mainPlayUrl;
    }

    // 获取全部备用源列表
    public List<String> getBackupUrls() {
        return backupUrls;
    }

    public String getName() {
        return name;
    }

    public String getGroup() {
        return group;
    }

    // 🟢【新增】设置分组，用于解析时动态更新分组名
    public void setGroup(String group) {
        this.group = group;
    }

    public String getChannelId() {
        return channelId;
    }

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
}
