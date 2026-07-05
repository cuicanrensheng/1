package com.tv.live.bean;

import java.util.ArrayList;
import java.util.List;

public class IptvChannel {
    private String channelId;
    private String channelName;
    private String groupName;
    private String logoUrl;
    private List<LineModel> sourceList;

    public IptvChannel() {
        sourceList = new ArrayList<>();
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public List<LineModel> getSourceList() {
        return sourceList;
    }

    public void setSourceList(List<LineModel> sourceList) {
        this.sourceList = sourceList;
    }
}
