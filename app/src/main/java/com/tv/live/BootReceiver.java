package com.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启广播接收器
 * 适配所有电视设备和所有安卓版本，支持多种开机场景
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    // ====================================================================
    // 支持的广播 Action 列表（多广播兼容，提高成功率）
    // ====================================================================
    private static final String ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED;
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED;
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_POWER_CONNECTED = Intent.ACTION_POWER_CONNECTED;

    // ====================================================================
    // 延迟启动时间（毫秒）
    // ====================================================================
    private static final long START_DELAY_MS = 3000;
    private static final long SHORT_DELAY_MS = 1000;

    // ====================================================================
    // 广播接收主方法
    // ====================================================================
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        // 🛡️【兼容旧电视】使用 goAsync() 让 BroadcastReceiver 的执行时间不受 10 秒限制
        // 在 Android 5.1.1 设备上极其重要，防止 PendingIntent 还没发送，进程就被杀了
        final PendingResult pendingResult = goAsync();

        String action = intent.getAction();
        Log.d(TAG, "收到广播：" + action);

        // ====================================================================
        // 第一步：判断是否是我们关心的广播
        // ====================================================================
        if (!isBootRelatedAction(action)) {
            Log.d(TAG, "非开机相关广播，忽略：" + action);
            pendingResult.finish();
            return;
        }

        // ====================================================================
        // 第二步：读取自启开关状态
        // ====================================================================
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        boolean autoStart = sp.getBoolean("boot_auto_start", false);
        Log.d(TAG, "开机自启开关状态：" + autoStart);

        if (!autoStart) {
            Log.d(TAG, "用户未开启开机自启，不启动");
            pendingResult.finish();
            return;
        }

        // ====================================================================
        // 第三步：根据广播类型决定延迟时间
        // ====================================================================
        long delay = getDelayByAction(action);
        Log.d(TAG, "延迟 " + delay + "ms 后启动应用");

        // ====================================================================
        // 第四步：延迟启动应用（用 AlarmManager 更可靠）
        // ====================================================================
        scheduleDelayedStart(context, delay);

        // ⚠️ 注意：虽然这里 finish 了，但因为 AlarmManager 是独立的系统服务，
        // 所以哪怕这个 Receiver 销毁了，3秒后依然会执行我们的 PendingIntent。
        pendingResult.finish();
    }

    // ====================================================================
    // 判断是否是开机相关的广播
    // ====================================================================
    private boolean isBootRelatedAction(String action) {
        if (action == null) return false;
        return action.equals(ACTION_BOOT_COMPLETED)
                || action.equals(ACTION_LOCKED_BOOT_COMPLETED)
                || action.equals(ACTION_MY_PACKAGE_REPLACED)
                || action.equals(ACTION_QUICKBOOT_POWERON)
                || action.equals(ACTION_QUICKBOOT_POWERON_HTC)
                || action.equals(ACTION_POWER_CONNECTED);
    }

    // ====================================================================
    // 根据广播类型获取延迟时间
    // ====================================================================
    private long getDelayByAction(String action) {
        if (ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return SHORT_DELAY_MS;
        }
        return START_DELAY_MS;
    }

    // ====================================================================
    // 调度延迟启动（用 AlarmManager 更可靠）
    // ====================================================================
    private void scheduleDelayedStart(Context context, long delayMs) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.e(TAG, "AlarmManager 不可用，尝试直接启动");
                startMainActivity(context);
                return;
            }

            Intent startIntent = new Intent(context, BootStartReceiver.class);
            startIntent.setAction("com.tv.live.START_APP");

            // 🔥【关键修复】Android 5.1.1 (API 22) 不支持 FLAG_IMMUTABLE
            // 我们将判断版本提高到了 Build.VERSION_CODES.S (Android 12)
            // 这样在 5.1.1 的机器上会走 else 分支，绝对不会报错！
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    startIntent,
                    flags
            );

            long triggerAt = System.currentTimeMillis() + delayMs;

            // ✅ 使用 set 非精确闹钟，降低旧电视省电模式的拦截
            alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
            );
            Log.d(TAG, "已设置延迟启动闹钟，" + delayMs + "ms 后启动");

        } catch (Exception e) {
            Log.e(TAG, "设置延迟启动失败，尝试直接启动", e);
            startMainActivity(context);
        }
    }

    // ====================================================================
    // 启动主页面（兜底方法）
    // ====================================================================
    private void startMainActivity(Context context) {
        try {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(mainIntent);
            Log.d(TAG, "兜底方案：已启动 MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "启动 MainActivity 失败", e);
        }
    }
}
