package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlaylistParser {

    // 🟢【新增】测速超时配置（毫秒）
    private static final int TEST_TIMEOUT = 3000;

    public static List<Channel> parse(String url) throws Exception {
        // 使用 LinkedHashMap 保证频道按直播源的解析顺序排列
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
                    // 频道已存在：作为备用源添加到 backupUrls 列表
                    existing.addBackupUrl(uri);
                    // 分组覆盖
                    if (group != null && !group.isEmpty()) {
                        existing.setGroup(group);
                    }
                } else {
                    // 频道不存在：新建（第一条作为主源 mainPlayUrl）
                    Channel newChannel = new Channel(name, uri, group, tvgId);
                    channelMap.put(key, newChannel);
                }
            }
        }
        br.close();

        // 🟢【核心新增】解析完成后，立即进行“测速 -> 剔除失效源”处理
        return filterValidChannels(new ArrayList<>(channelMap.values()));
    }

    // ====================================================================
    // 🟢【新增】测速 & 无效源剔除工具
    // ====================================================================

    /**
     * 检测单个 URL 是否可用
     */
    private static boolean isUrlValid(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(TEST_TIMEOUT);
            conn.setReadTimeout(TEST_TIMEOUT);
            conn.setRequestMethod("GET");
            // 只请求头部少量数据，降低资源消耗
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            conn.disconnect();
            // 只要返回 200~399 均视为有效（包括 206  Partial Content）
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 遍历并过滤频道列表：
     * 1. 剔除所有线路都失效的频道
     * 2. 如果主源失效，自动将第一条可用的备用源提升为主源
     */
    private static List<Channel> filterValidChannels(List<Channel> channels) {
        List<Channel> result = new ArrayList<>();
        for (Channel ch : channels) {
            // 1. 检测主源
            boolean mainValid = isUrlValid(ch.getMainPlayUrl());

            // 2. 检测备用源，收集有效的备用源
            List<String> validBackups = new ArrayList<>();
            for (String backup : ch.getBackupUrls()) {
                if (isUrlValid(backup)) {
                    validBackups.add(backup);
                }
            }

            // 3. 如果主源和备用源全部失效，直接跳过此频道（自动剔除）
            if (!mainValid && validBackups.isEmpty()) {
                continue;
            }

            // 4. 重建有效的 Channel 对象
            String newMainUrl;
            List<String> newBackups = new ArrayList<>();

            if (mainValid) {
                newMainUrl = ch.getMainPlayUrl();
                newBackups = validBackups; // 直接复用有效的备用源
            } else {
                // 主源失效，取第一个有效备用源当主源
                newMainUrl = validBackups.remove(0);
                newBackups = validBackups; // 剩下的作为新的备用源
            }

            // 重新创建频道对象（保持原分组、名称、tvgId）
            Channel newChannel = new Channel(ch.getName(), newMainUrl, ch.getGroup(), ch.getChannelId());
            for (String u : newBackups) {
                newChannel.addBackupUrl(u);
            }
            result.add(newChannel);
        }
        return result;
    }
}
