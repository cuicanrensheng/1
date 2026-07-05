package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
        // 🟢 替换原有的 List<Channel> list，改用 Map 去重
        Map<String, Channel> channelMap = new LinkedHashMap<>();
        
        BufferedReader br = new BufferedReader(new InputStreamReader(new URL(url).openStream()));
        String line;
        String currentGroup = "未分类";

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
                if (uri != null && uri.startsWith("http")) {
                    // 🟢 核心替换：如果已经有同名频道，把当前地址当成备用源加进去
                    if (channelMap.containsKey(name)) {
                        Channel existingChannel = channelMap.get(name);
                        existingChannel.addBackupUrl(uri);
                    } else {
                        // 如果是新频道，正常创建并放入 Map
                        Channel newChannel = new Channel(name, uri, group, tvgId);
                        channelMap.put(name, newChannel);
                    }
                }
            }
        }
        br.close();

        // 🟢 最后将 Map 中存储的所有频道转回 List 返回
        return new ArrayList<>(channelMap.values());
    }
}
