package com.iptvlive.bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道实体类
 * 1.基础属性：频道名、分组、tvgId、主链接、备用链接
 * 2.单频道私有Refer/Cookie（优先级>全局Header）
 * 3.M3U标签自动解析分辨率、码率，无参数默认FHD/3.3MB/s
 */
public class ChannelBean {
    //频道名称
    public String name;
    //频道分组名称
    public String group;
    //EPG匹配ID，对应xml内channel字段
    public String tvgId;
    //主播放地址
    public String url;
    //备用线路集合
    public List<String> backupUrls;

    //单频道独立Referer
    public String chRefer;
    //单频道独立Cookie
    public String chCookie;

    //分辨率，M3U标签resolution=xxx自动解析，默认FHD
    public String resolution = "FHD";
    //码率，M3U标签bit=xxx自动解析，默认3.3MB/s
    public String bitrate = "3.3MB/s";

    /**
     * 获取全部播放地址（主+备用）
     */
    public List<String> getAllSource() {
        List<String> allSource = new ArrayList<>();
        allSource.add(url);
        if (backupUrls != null) {
            allSource.addAll(backupUrls);
        }
        return allSource;
    }
}
