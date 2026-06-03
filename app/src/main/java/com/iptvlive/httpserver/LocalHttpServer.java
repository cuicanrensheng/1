package com.iptvlive.httpserver;

import android.content.Context;
import com.iptvlive.receiver.RefreshSubReceiver;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.HttpHeaderSpUtil;
import com.iptvlive.util.LogSpUtil;
import fi.iki.elonen.NanoHTTPD;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 局域网WEB服务 10481端口
 * WEB页面读取assets/config_web.html
 * 接口：保存Header、添加M3U、添加EPG
 */
public class LocalHttpServer extends NanoHTTPD {
    private Context mCtx;

    public LocalHttpServer(int port, Context ctx) {
        super(port);
        mCtx = ctx;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();
        try {
            //保存全局Header接口
            if (uri.equals("/api/saveHeader") && method == Method.POST) {
                String ua = params.get("ua");
                String ref = params.get("ref");
                String ck = params.get("ck");
                HttpHeaderSpUtil.setUA(ua);
                HttpHeaderSpUtil.setReferer(ref);
                HttpHeaderSpUtil.setCookie(ck);
                LogSpUtil.addOperCrashLog("【WEB保存全局Header成功】");
                return newFixedLengthResponse("ok");
            }
            //添加M3U订阅接口
            if (uri.equals("/api/addSub") && method == Method.POST) {
                String subUrl = params.get("url");
                List<String> subList = AppSpUtil.getSubSourceList();
                if (!subList.contains(subUrl)) subList.add(subUrl);
                AppSpUtil.saveSubSourceList(subList);
                AppSpUtil.setCurSubUrl(subUrl);
                RefreshSubReceiver.loadSubSource(subUrl);
                LogSpUtil.addOperCrashLog("【WEB新增M3U】" + subUrl);
                return newFixedLengthResponse("ok");
            }
            //添加EPG接口
            if (uri.equals("/api/addEpg") && method == Method.POST) {
                String epgUrl = params.get("url");
                List<String> epgList = AppSpUtil.getEpgSourceList();
                if (!epgList.contains(epgUrl)) epgList.add(epgUrl);
                AppSpUtil.saveEpgSourceList(epgList);
                AppSpUtil.setCurEpgUrl(epgUrl);
                LogSpUtil.addOperCrashLog("【WEB新增EPG】" + epgUrl);
                return newFixedLengthResponse("ok");
            }
            //返回前端网页
            InputStream is = mCtx.getAssets().open("config_web.html");
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, is, is.available());
        } catch (Exception e) {
            return newFixedLengthResponse("err:" + e.getMessage());
        }
    }
}
