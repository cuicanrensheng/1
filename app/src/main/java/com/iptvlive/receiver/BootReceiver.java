package com.iptvlive.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.iptvlive.activity.PlayActivity;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.AutoRefreshUtil;

/**
 * 开机广播接收器
 * 开启开机自启：开机自动启动播放APP
 * 开启定时刷新：开机启动闹钟定时任务
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        //监听开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            //判断开机自启开关
            if (AppSpUtil.getBootStart()) {
                Intent startApp = new Intent(context, PlayActivity.class);
                startApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(startApp);
            }
            //自动刷新开启则启动定时闹钟
            if (AppSpUtil.getAutoRefreshSub()) {
                AutoRefreshUtil.startRefreshTask(context, AppSpUtil.getAutoRefreshHour());
            }
        }
    }
}
