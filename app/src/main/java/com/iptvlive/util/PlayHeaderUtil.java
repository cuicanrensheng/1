package com.iptvlive.util;

import com.iptvlive.bean.ChannelBean;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放头优先级工具
 * UA固定全局
 * Refer/Cookie：频道自定义>全局
 */
public class PlayHeaderUtil {
    public static Map<String, String> getPlayHeader(ChannelBean channel) {
        Map<String, String> header = new HashMap<>();
        //UA永远全局
        String ua = HttpHeaderSpUtil.getUA();
        if (!ua.isEmpty()) header.put("User-Agent", ua);

        //Refer频道优先
        String useRef;
        if (channel != null && channel.chRefer != null && !channel.chRefer.trim().isEmpty()) {
            useRef = channel.chRefer.trim();
        } else {
            useRef = HttpHeaderSpUtil.getReferer();
        }
        if (!useRef.isEmpty()) header.put("Referer", useRef);

        //Cookie频道优先
        String useCk;
        if (channel != null && channel.chCookie != null && !channel.chCookie.trim().isEmpty()) {
            useCk = channel.chCookie.trim();
        } else {
            useCk = HttpHeaderSpUtil.getCookie();
        }
        if (!useCk.isEmpty()) header.put("Cookie", useCk);
        return header;
    }
}
