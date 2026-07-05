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
        // 🟢 使用 LinkedHashMap 严格保留解析顺序：最先读到的 URL 自动成为主源！
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

                // 🟢【核心逻辑】优先使用 tvg-id 作为唯一合并键，防止不同频道的 tvg-name 撞名！
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
                        // 已存在同名频道，将此地址追加为备用源
                        Channel existingChannel = channelMap.get(uniqueKey);
                        existingChannel.addBackupUrl(uri);
                    } else {
                        // 第一次出现，作为主源创建（保留 map 插入顺序）
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
