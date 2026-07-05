package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.text.TextUtils;

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
        // 🟢 使用 LinkedHashMap 保证解析顺序，最先出现的作为主源
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
                String tvgId = "";
                String tvgName = "";
                String displayName = "";

                // 提取 tvg-id
                if (line.contains("tvg-id=\"")) {
                    tvgId = line.split("tvg-id=\"")[1].split("\"")[0];
                }
                // 提取 tvg-name
                if (line.contains("tvg-name=\"")) {
                    tvgName = line.split("tvg-name=\"")[1].split("\"")[0];
                }
                if (line.contains(",")) {
                    displayName = line.substring(line.indexOf(",") + 1).trim();
                }

                // 🟢【去重逻辑】优先使用 tvg-id，其次 tvg-name，最后显示名
                String uniqueKey = tvgId;
                if (TextUtils.isEmpty(uniqueKey)) {
                    uniqueKey = tvgName;
                }
                if (TextUtils.isEmpty(uniqueKey)) {
                    uniqueKey = displayName;
                }

                if (TextUtils.isEmpty(uniqueKey)) {
                    continue;
                }

                String uri = br.readLine();
                if (uri != null && uri.startsWith("http")) {
                    if (channelMap.containsKey(uniqueKey)) {
                        // 遇到同名（同一频道的不同链接），加入备用源
                        Channel existingChannel = channelMap.get(uniqueKey);
                        existingChannel.addBackupUrl(uri);
                    } else {
                        // 第一次出现，作为主源创建
                        String finalName = (tvgName != null && !tvgName.isEmpty()) ? tvgName : displayName;
                        Channel newChannel = new Channel(finalName, uri, currentGroup, tvgId);
                        channelMap.put(uniqueKey, newChannel);
                    }
                }
            }
        }
        br.close();

        return new ArrayList<>(channelMap.values());
    }
}
