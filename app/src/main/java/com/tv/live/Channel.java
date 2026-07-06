package com.tv.live;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    private String name;
    private String group;
    private String channelId;

    // 🟢 新增：存放该频道所有的线路地址
    private List<String> urls = new ArrayList<>();
    // 🟢 新增：当前选中的线路索引（0 代表主源）
    private int currentLineIndex = 0;

    public Channel(String name, String playUrl, String group, String channelId) {
        this.name = name;
        this.group = group;
        this.channelId = channelId;
        // 构造时，将传入的播放地址作为第一条线路（主源）
        this.urls.add(playUrl);
    }

    public String getName() { return name; }

    // 🟢 修改：动态返回当前选中的线路
    public String getPlayUrl() {
        if (urls == null || urls.isEmpty()) return null;
        // 如果用户设置里选的线路索引不存在（比如源失效被删了），自动切回主源
        if (currentLineIndex >= urls.size()) {
            currentLineIndex = 0;
        }
        return urls.get(currentLineIndex);
    }

    public String getGroup() { return group; }
    public String getChannelId() { return channelId; }

    // 🟢 新增：获取所有线路列表
    public List<String> getUrls() { return urls; }
    public void setUrls(List<String> urls) { this.urls = urls; }

    // 🟢 新增：获取/设置当前线路索引
    public int getCurrentLineIndex() { return currentLineIndex; }
    public void setCurrentLineIndex(int index) { this.currentLineIndex = index; }

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
