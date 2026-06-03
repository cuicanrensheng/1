package com.iptvlive.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.iptvlive.receiver.RefreshSubReceiver;

/**
 * 闹钟定时工具：开启/关闭定时刷新任务
 */
public class AutoRefreshUtil {
    private static final int REQ_CODE = 9911;

    /**
     * 开启定时刷新，hour=间隔小时
     */
    public static void startRefreshTask(Context ctx, int hour) {
        AlarmManager alarm = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, RefreshSubReceiver.class);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_CODE, intent, flag);
        long ms = hour * 3600L * 1000;
        alarm.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + ms, ms, pi);
    }

    /**
     * 取消定时刷新
     */
    public static void stopRefreshTask(Context ctx) {
        AlarmManager alarm = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, RefreshSubReceiver.class);
        int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_CODE, intent, flag);
        alarm.cancel(pi);
    }
}
