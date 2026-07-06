package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaylistParser {
    public static List<Channel> parse(String url) throws Exception {
        // 🟢 核心修改：使用 Map 代替 List，以频道名/tvgId作为 Key 进行去重合并
        Map<String, Channel> channelMap = new HashMap<>();
        
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
                // 提取分组
                if (line.contains("group-title=\"")) {
                    try {
                        group = line.split("group-title=\"")[1].split("\"")[0].trim();
                    } catch (Exception ignored) {}
                }
                // 提取频道名称（去逗号后的内容）
                if (line.contains(",")) {
                    name = line.substring(line.indexOf(",") + 1).trim();
                }

                String uri = br.readLine();
                if (uri != null && uri.startsWith("http")) {
                    // 🟢 核心修改：生成唯一 key，强制 trim 去除空格
                    String key = !tvgId.isEmpty() ? tvgId : name;
                    // 如果 tvgId 和 name 都是空的，直接跳过
                    if (key.isEmpty()) continue;

                    Channel existing = channelMap.get(key);
                    if (existing != null) {
                        // 🟢 频道已存在：将新地址加入备用列表（去除重复地址）
                        if (!existing.getUrls().contains(uri)) {
                            existing.getUrls().add(uri);
                        }
                    } else {
                        // 🟢 频道不存在：新建 Channel（注意此时 Channel 的构造方法会自动把 url 加入 urls 列表）
                        Channel newChannel = new Channel(name, uri, group, tvgId);
                        // 确保如果没有 tvgId，但频道名称被当作 key，我们依然把 key 保持正确
                        channelMap.put(key, newChannel);
                    }
                }
            }
        }
        br.close();

        // 🟢 最后将 Map 的所有 value 转成 List 返回
        return new ArrayList<>(channelMap.values());
    }
}
