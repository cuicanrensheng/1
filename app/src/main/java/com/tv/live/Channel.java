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

    // 构造器：初始化备用集合
    public Channel(String name, String mainPlayUrl, String group, String channelId) {
        this.name = name;
        this.mainPlayUrl = mainPlayUrl;
        this.group = group;
        this.channelId = channelId;
        this.backupUrls = new ArrayList<>();
    }

    // 添加备用源
    public void addBackupUrl(String url) {
        if (!backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // Getter
    public String getName() {
        return name;
    }

    public String getMainPlayUrl() {
        return mainPlayUrl;
    }

    public List<String> getBackupUrls() {
        return backupUrls;
    }

    public String getGroup() {
        return group;
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
