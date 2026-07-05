package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.text.TextUtils; // 🟢 别忘了导入这个

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
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

                if (line.contains("tvg-id=\"")) {
                    tvgId = line.split("tvg-id=\"")[1].split("\"")[0];
                }
                if (line.contains("tvg-name=\"")) {
                    tvgName = line.split("tvg-name=\"")[1].split("\"")[0];
                }
                if (line.contains(",")) {
                    displayName = line.substring(line.indexOf(",") + 1).trim();
                }

                // 🟢【核心修复】无论 tvg-id 是否相同，强制以 tvg-name 作为去重合并的唯一 Key！
                // 解决同一个台 (比如 CCTV5) 出现多个不同 tvg-id 导致无法合并的难题。
                String uniqueKey = tvgName; 
                if (TextUtils.isEmpty(uniqueKey)) {
                    uniqueKey = tvgId;
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
                        Channel existingChannel = channelMap.get(uniqueKey);
                        existingChannel.addBackupUrl(uri);
                    } else {
                        // 以 tvgName 作为实际的显示名，如果 tvgName 为空则回退到显示名
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
