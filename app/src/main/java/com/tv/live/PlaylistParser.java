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

        Channel lastChannel = null;
        int continuousSameCount = 0;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTGRP:")) {
                currentGroup = line.substring(8).trim();
                continue;
            }

            if (line.startsWith("#EXTINF:")) {
                String name = "";
                String tvgId = "";
                String group = currentGroup;

                if (line.contains("tvg-id=\"")) {
                    tvgId = line.split("tvg-id=\"")[1].split("\"")[0];
                }
                if (line.contains("group-title=\"")) {
                    group = line.split("group-title=\"")[1].split("\"")[0];
                }
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                String uri = br.readLine();
                if (uri == null || !uri.startsWith("http")) {
                    continue;
                }

                // 判断是否与上一个频道主链接完全相同
                if (lastChannel != null && uri.equals(lastChannel.getMainPlayUrl())) {
                    continuousSameCount++;
                    // 仅连续3~4条同源链接作为备用源存入
                    if (continuousSameCount <= 4) {
                        lastChannel.addBackupUrl(uri);
                    }
                    // 不新建频道，直接跳过本轮
                    continue;
                } else {
                    // 全新频道，创建并加入列表
                    Channel newChannel = new Channel(name, uri, group, tvgId);
                    resultList.add(newChannel);
                    lastChannel = newChannel;
                    continuousSameCount = 0;
                }
            }
        }
        br.close();
        return resultList;
    }
}
