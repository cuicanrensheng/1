package com.iptvlive.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.iptvlive.bean.ChannelBean;
import com.iptvlive.bean.EpgInfoBean;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.EpgXmlParserUtil;
import com.iptvlive.util.LogSpUtil;
import com.iptvlive.util.M3UParserUtil;
import java.io.File;
import java.util.List;

/**
 * 定时刷新广播
 * 闹钟定时触发：重新拉取M3U、重新下载EPG、更新全局缓存
 * 全局静态集合：全项目共用频道列表、EPG列表
 */
public class RefreshSubReceiver extends BroadcastReceiver {
    //全局缓存所有解析后的频道
    public static List<ChannelBean> globalChannelList;
    //全局缓存所有EPG节目数据
    public static List<EpgInfoBean> globalEpgList;

    @Override
    public void onReceive(Context context, Intent intent) {
        //读取当前生效订阅地址
        String subUrl = AppSpUtil.getCurSubUrl();
        //读取当前生效EPG地址
        String epgUrl = AppSpUtil.getCurEpgUrl();

        //刷新M3U频道数据
        boolean parseOk = loadSubSource(subUrl);

        //刷新EPG节目数据
        if (epgUrl != null && !epgUrl.isEmpty()) {
            File epgCache = new File(context.getFilesDir(), EpgXmlParserUtil.EPG_CACHE_NAME);
            boolean downOk = EpgXmlParserUtil.downloadEpgXml(epgUrl, epgCache);
            if (downOk) {
                globalEpgList = EpgXmlParserUtil.parseLocalEpgFile(epgCache);
            }
        }

        //记录刷新日志
        if (parseOk) {
            LogSpUtil.addParseLog("【定时自动刷新源成功】地址：" + subUrl);
        } else {
            LogSpUtil.addParseLog("【定时自动刷新源失败】地址：" + subUrl);
        }
    }

    /**
     * 加载M3U订阅，赋值全局频道集合
     */
    public static boolean loadSubSource(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return false;
        }
        List<ChannelBean> tempList = M3UParserUtil.parseNetM3U(sourceUrl);
        if (tempList == null || tempList.isEmpty()) {
            globalChannelList = null;
            return false;
        }
        globalChannelList = tempList;
        return true;
    }
}
