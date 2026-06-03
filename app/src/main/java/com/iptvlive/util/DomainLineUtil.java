package com.iptvlive.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 域名优选工具：可用域名线路优先排在前面
 */
public class DomainLineUtil {
    //截取URL域名
    public static String getDomain(String url) {
        try {
            URL u = new URL(url);
            return u.getHost();
        } catch (MalformedURLException e) {
            return "";
        }
    }

    //按白名单排序线路，优选域名靠前
    public static List<String> sortLineByOkDomain(List<String> allUrls, List<String> okDomList) {
        List<String> priority = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String url : allUrls) {
            String dom = getDomain(url);
            if (okDomList.contains(dom)) {
                priority.add(url);
            } else {
                other.add(url);
            }
        }
        priority.addAll(other);
        return priority;
    }

    //播放成功域名加入白名单
    public static void addSuccessDomain(String url) {
        String dom = getDomain(url);
        if (dom.isEmpty()) return;
        List<String> list = AppSpUtil.getOkDomainList();
        if (!list.contains(dom)) {
            list.add(dom);
            AppSpUtil.saveOkDomainList(list);
        }
    }

    //失效域名移出白名单
    public static void removeFailDomain(String url) {
        String dom = getDomain(url);
        if (dom.isEmpty()) return;
        List<String> list = AppSpUtil.getOkDomainList();
        list.remove(dom);
        AppSpUtil.saveOkDomainList(list);
    }
}
