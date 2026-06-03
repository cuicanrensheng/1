package com.iptvlive.bean;

/**
 * EPG单节目实体
 * channelId：匹配频道tvgId
 * startTime/endTime：时间格式yyyyMMddHHmmss
 * proName：节目名称
 */
public class EpgInfoBean {
    public String channelId;
    public String startTime;
    public String endTime;
    public String proName;
}
