package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
        List<Channel> resultList = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(new URL(url).openStream()));
        String line;
        String currentGroup = "未分类";

        // 缓存上一个频道，用于合并同源备用源
        Channel lastChannel = null;
        // 记录当前连续同源重复次数
        int sameUrlCount = 0;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTM3U")) continue;

            // 更新全局分组
            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            // 解析频道信息行
            if (line.startsWith("#EXTINF:")) {
                String name = "";
                String tvgId = "";
                String group = currentGroup;

                // 提取 tvg-id
                if (line.contains("tvg-id=\"")) {
                    tvgId = line.split("tvg-id=\"")[1].split("\"")[0];
                }
                // 提取分组标题
                if (line.contains("group-title=\"")) {
                    group = line.split("group-title=\"")[1].split("\"")[0];
                }
                // 提取频道名称
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                // 读取播放地址行
                String uri = br.readLine();
                if (uri == null || !uri.startsWith("http")) {
                    continue;
                }

                // ========== 同源合并核心逻辑 ==========
                if (lastChannel != null && uri.equals(lastChannel.getMainPlayUrl())) {
                    // 和上一个频道主地址完全相同，判定为备用源
                    sameUrlCount++;
                    // 仅当连续重复总数3~4条时作为备用源合并
                    if (sameUrlCount >= 1 && sameUrlCount <= 4) {
                        lastChannel.addBackupUrl(uri);
                    }
                    // 超过4条不再追加，避免冗余
                    if (sameUrlCount > 4) {
                        continue;
                    }
                } else {
                    // 新的不同源频道，新建对象
                    Channel newChannel = new Channel(name, uri, group, tvgId);
                    resultList.add(newChannel);
                    // 重置缓存与计数
                    lastChannel = newChannel;
                    sameUrlCount = 0;
                }
            }
        }
        br.close();
        return resultList;
    }
}
