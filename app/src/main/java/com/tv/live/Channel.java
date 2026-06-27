package com.tv.live;
public class Channel {
    private String name;
    private String playUrl;
    private String group;
    private String channelId;
    public Channel(String name, String playUrl, String group, String channelId) {
        this.name = name;
        this.playUrl = playUrl;
        this.group = group;
        this.channelId = channelId;
    }
    public String getName() { return name; }
    public String getPlayUrl() { return playUrl; }
    public String getGroup() { return group; }
    public String getChannelId() { return channelId; }

    public static class EpgItem {
        public String dayName;
        public String time;
        // 新增：节目开始、结束时间字段
        public String startTime;
        public String endTime;
        public String title;
        public boolean isPlaying;

        // 更新构造函数，增加起止时间入参
        public EpgItem(String dayName, String time, String startTime, String endTime, String title, boolean isPlaying) {
            this.dayName = dayName;
            this.time = time;
            this.startTime = startTime;
            this.endTime = endTime;
            this.title = title;
            this.isPlaying = isPlaying;
        }

        // 需求要求两个get方法
        public String getStartTimeStr() {
            return startTime;
        }
        public String getEndTimeStr() {
            return endTime;
        }

        public String getReplayUrl() {
            return null;
        }
    }
}
