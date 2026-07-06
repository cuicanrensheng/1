package com.tv.live;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaylistParser {
    // 🟢 新增：豆包提供的稳健正则表达式（提取 tvg-id, tvg-name, logo, group-title）
    private static final Pattern EXTINF_FULL_PATTERN = Pattern.compile(
            "#EXTINF:-?\\d+,(.*?)(\\s+tvg-id=\"([^\"]*)\")?(\\s+tvg-name=\"([^\"]*)\")?(\\s+logo=\"([^\"]*)\")?(\\s+group-title=\"([^\"]*)\")?"
    );
    // 🟢 新增：频道名清洗（去特殊符号、去高清/4K等冗余后缀）
    private static final Pattern CHANNEL_SYMBOL_CLEAN = Pattern.compile("[★☆◆◇■□▲▽#@&*]");
    private static final Pattern CHANNEL_SUFFIX_CLEAN = Pattern.compile("[-_ ]?(高清|超清|蓝光|标清|4K|HD|SD|VIP|付费|直播|源|线路|①②③④⑤67890)");

    public static List<Channel> parse(String url) throws Exception {
        // 使用 Map 去重合并（沿用您原有的逻辑）
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

                // 🟢【核心替换】放弃脆弱的 split，使用豆包的防弹级正则表达式提取
                Matcher matcher = EXTINF_FULL_PATTERN.matcher(line);
                if (matcher.matches()) {
                    name = matcher.group(1) != null ? matcher.group(1).trim() : "";
                    tvgId = matcher.group(3) != null ? matcher.group(3).trim() : "";
                    String groupTitle = matcher.group(9) != null ? matcher.group(9).trim() : "";
                    if (!groupTitle.isEmpty()) {
                        group = groupTitle;
                    }
                }

                String uri = br.readLine();
                if (uri != null && uri.startsWith("http")) {
                    // 🟢【核心修改】生成唯一 Key 之前，先对频道名进行标准化清洗
                    String cleanedName = cleanChannelName(name);
                    // 优先使用 tvgId，没有 tvgId 则使用清洗后的标准名
                    String key = !tvgId.isEmpty() ? tvgId : cleanedName;
                    if (key.isEmpty()) continue;

                    Channel existing = channelMap.get(key);
                    if (existing != null) {
                        // 已存在：追加备用源并去重
                        if (!existing.getUrls().contains(uri)) {
                            existing.getUrls().add(uri);
                        }
                    } else {
                        // 新建 Channel
                        Channel newChannel = new Channel(name, uri, group, tvgId);
                        channelMap.put(key, newChannel);
                    }
                }
            }
        }
        br.close();
        return new ArrayList<>(channelMap.values());
    }

    // 🟢 新增：频道名标准化清洗方法
    private static String cleanChannelName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        String temp = rawName.trim();
        // 1. 去除特殊符号
        temp = CHANNEL_SYMBOL_CLEAN.matcher(temp).replaceAll("");
        // 2. 循环去除冗余后缀（如 "高清","4K"等）
        Matcher suffixMatcher = CHANNEL_SUFFIX_CLEAN.matcher(temp);
        while (suffixMatcher.find()) {
            temp = suffixMatcher.replaceAll("");
            suffixMatcher = CHANNEL_SUFFIX_CLEAN.matcher(temp);
        }
        return temp.trim();
    }
}
