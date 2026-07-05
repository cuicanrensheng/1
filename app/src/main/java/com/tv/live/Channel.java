package com.tv.live;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    private String name;
    private String playUrl;
    private String group;
    private String channelId;
    
    // 🟢 新增：备用地址列表
    private List<String> backupUrls;

    public Channel(String name, String playUrl, String group, String channelId) {
        this.name = name;
        this.playUrl = playUrl;
        this.group = group;
        this.channelId = channelId;
        // 🟢 初始化备用地址列表
        this.backupUrls = new ArrayList<>();
    }

    public String getName() { return name; }
    public String getPlayUrl() { return playUrl; }
    public String getGroup() { return group; }
    public String getChannelId() { return channelId; }

    // 🟢 新增：添加一个备用地址
    public void addBackupUrl(String url) {
        if (url != null && !url.isEmpty() && !backupUrls.contains(url)) {
            backupUrls.add(url);
        }
    }

    // 🟢 新增：判断是否有备用地址
    public boolean hasBackupUrl() {
        return backupUrls != null && !backupUrls.isEmpty();
    }

    // 🟢 新增：获取所有备用地址列表
    public List<String> getBackupUrls() {
        return backupUrls;
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
