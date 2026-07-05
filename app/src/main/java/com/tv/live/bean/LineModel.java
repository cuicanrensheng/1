package com.tv.live.bean;

public class LineModel {
    private String lineName;
    private String sourceUrl;
    private int failCount;
    private boolean enableAutoSwitch;

    public LineModel(String lineName, String sourceUrl) {
        this.lineName = lineName;
        this.sourceUrl = sourceUrl;
        this.failCount = 0;
        this.enableAutoSwitch = true;
    }

    public String getLineName() {
        return lineName;
    }

    public void setLineName(String lineName) {
        this.lineName = lineName;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public boolean isEnableAutoSwitch() {
        return enableAutoSwitch;
    }

    public void setEnableAutoSwitch(boolean enableAutoSwitch) {
        this.enableAutoSwitch = enableAutoSwitch;
    }
}
