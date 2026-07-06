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
        // 🟢 修复1：使用 LinkedHashMap 保证频道按直播源的解析顺序排列，避免乱序
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

                // 提取 tvg-id
                if (line.contains("tvg-id=\"")) {
                    try {
                        tvgId = line.split("tvg-id=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取 group-title
                if (line.contains("group-title=\"")) {
                    try {
                        group = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取频道名称
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                String uri = br.readLine();
                if (uri == null || !uri.startsWith("http")) continue;

                // 全局去重：优先使用 tvg-id，没有则用频道名作为 Key
                String key = !tvgId.isEmpty() ? tvgId : name;
                if (key.isEmpty()) continue;

                Channel existing = channelMap.get(key);
                if (existing != null) {
                    // ✅ 频道已存在：作为备用源添加到 backupUrls 列表
                    existing.addBackupUrl(uri);
                    // 🟢【核心修改】只要解析到有效的分组名称，就无条件覆盖旧分组！
                    if (group != null && !group.isEmpty()) {
                        existing.setGroup(group);
                    }
                } else {
                    // ✅ 频道不存在：新建（第一条作为主源 mainPlayUrl）
                    Channel newChannel = new Channel(name, uri, group, tvgId);
                    channelMap.put(key, newChannel);
                }
            }
        }
        br.close();
        // 将 Map 的所有 value 转成 List 返回
        return new ArrayList<>(channelMap.values());
    }
}
