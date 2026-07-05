package com.tv.live.util;

import com.tv.live.bean.IptvChannel;
import com.tv.live.bean.LineModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uParser {
    private static final Pattern PATTERN_TVG_ID = Pattern.compile("tvg-id=\"(.*?)\"");
    private static final Pattern PATTERN_GROUP = Pattern.compile("group-title=\"(.*?)\"");

    public static List<IptvChannel> parseM3u(String content) {
        Map<String, IptvChannel> channelMap = new HashMap<>();
        String[] allLines = content.split("\n");
        String tempName = "未知频道";
        String tempTvgId = "";
        String tempGroup = "默认分组";

        for (String rawLine : allLines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                // 提取频道名称
                String[] nameSplit = line.split(",");
                if (nameSplit.length > 1) {
                    tempName = nameSplit[nameSplit.length - 1];
                }
                // 提取tvg-id
                Matcher tvgMatcher = PATTERN_TVG_ID.matcher(line);
                if (tvgMatcher.find()) {
                    tempTvgId = tvgMatcher.group(1);
                } else {
                    tempTvgId = tempName;
                }
                // 提取分组
                Matcher groupMatcher = PATTERN_GROUP.matcher(line);
                if (groupMatcher.find()) {
                    tempGroup = groupMatcher.group(1);
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                // URL行，拆分$线路标记
                String[] urlSplit = line.split("\\$");
                String sourceUrl = urlSplit[0];
                String lineTag = urlSplit.length > 1 ? urlSplit[1] : "主线路";
                LineModel lineModel = new LineModel(lineTag, sourceUrl);

                if (channelMap.containsKey(tempTvgId)) {
                    // 同频道追加备用线路
                    channelMap.get(tempTvgId).getSourceList().add(lineModel);
                } else {
                    // 新建频道
                    IptvChannel newChannel = new IptvChannel();
                    newChannel.setChannelId(tempTvgId);
                    newChannel.setChannelName(tempName);
                    newChannel.setGroupName(tempGroup);
                    newChannel.getSourceList().add(lineModel);
                    channelMap.put(tempTvgId, newChannel);
                }
            }
        }
        return new ArrayList<>(channelMap.values());
    }
}
