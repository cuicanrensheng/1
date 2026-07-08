package com.tv.live;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新管理器
 *
 * 【功能】
 * 1. 检查更新（请求服务器 JSON 配置）
 * 2. 版本号对比
 * 3. 显示更新对话框（含更新日志）
 * 4. 下载 APK（使用系统 DownloadManager）
 * 5. 下载完成后自动安装
 *
 * 【使用方式】
 * UpdateManager updateManager = new UpdateManager(context);
 * updateManager.checkUpdate();
 *
 * 【JSON 配置格式】
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "downloadUrl": "https://xxx.com/app.apk",
 *   "updateLog": "1. 修复xxx\n2. 新增xxx",
 *   "forceUpdate": false
 * }
 */
public class UpdateManager {
    // ====================== 常量 ======================
    /** 版本配置文件地址 */
    private static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json";

    /** 下载文件名称 */
    private static final String APK_FILE_NAME = "tv_live_update.apk";

    // 🟢【修复1】全局主线程 Handler，彻底取代危险的 Activity 强转
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 🟢【修复2】状态锁，防止快速连续点击导致后台线程堆积
    private static boolean isChecking = false;
    private static boolean isDownloading = false;

    // ====================== 成员变量 ======================
    /** 上下文 */
    private final Context context;

    /** 下载管理器 */
    private DownloadManager downloadManager;

    /** 下载任务 ID */
    private long downloadId = -1;

    /** 下载完成广播接收器 */
    private BroadcastReceiver downloadCompleteReceiver;

    // ====================== 构造函数 ======================
    public UpdateManager(Context context) {
        this.context = context;
    }

    // ====================================================================
    // 1. 检查更新
    // ====================================================================
    public void checkUpdate() {
        // 🟢【修复2】防连点锁
        synchronized (UpdateManager.class) {
            if (isChecking) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在检查更新中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isChecking = true;
        }

        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_JSON_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("HTTP 错误：" + responseCode);
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                is.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int latestVersionCode = json.getInt("versionCode");
                String latestVersionName = json.getString("versionName");
                String downloadUrl = json.getString("downloadUrl");
                String updateLog = json.optString("updateLog", "");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);

                int currentVersionCode = 0;
                String currentVersionName = "未知";
                try {
                    currentVersionCode = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionCode;
                    currentVersionName = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final int finalCurrentVersionCode = currentVersionCode;
                final String finalCurrentVersionName = currentVersionName;
                final String finalLatestVersionName = latestVersionName;
                final String finalDownloadUrl = downloadUrl;
                final String finalUpdateLog = updateLog;
                final boolean finalForceUpdate = forceUpdate;

                // 🟢【修复1】切回主线程时使用 Handler，并释放锁
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) {
                        isChecking = false;
                    }
                    // 🟢【修复3】检查 Activity 是否存活，防止 BadTokenException
                    if (context instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) context;
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                    }

                    if (latestVersionCode > finalCurrentVersionCode) {
                        showUpdateDialog(
                                finalCurrentVersionName,
                                finalLatestVersionName,
                                finalUpdateLog,
                                finalDownloadUrl,
                                finalForceUpdate
                        );
                    } else {
                        Toast.makeText(context,
                                "已是最新版本\n当前版本：" + finalCurrentVersionName,
                                Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) {
                        isChecking = false;
                    }
                    // 🟢【修复3】检查 Activity 是否存活
                    if (context instanceof android.app.Activity) {
                        android.app.Activity activity = (android.app.Activity) context;
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                    }
                    Toast.makeText(context, "检查更新失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ====================================================================
    // 2. 显示更新对话框
    // ====================================================================
    private void showUpdateDialog(String currentVersion, String latestVersion,
                                   String updateLog, String downloadUrl,
                                   boolean forceUpdate) {
        // 🟢【修复3】在弹窗前再次确认 Activity 存活
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
        }

        String message = "发现新版本！\n\n"
                + "当前版本：" + currentVersion + "\n"
                + "最新版本：" + latestVersion + "\n\n"
                + "【更新内容】\n"
                + updateLog;

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("📥 发现新版本")
                .setMessage(message)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    startDownload(downloadUrl);
                });

        if (!forceUpdate) {
            builder.setNegativeButton("稍后再说", null);
        }
        builder.setCancelable(!forceUpdate);
        builder.show();
    }

    // ====================================================================
    // 3. 开始下载 APK
    // ====================================================================
    private void startDownload(String downloadUrl) {
        // 🟢【修复2】加入下载防并发锁
        synchronized (UpdateManager.class) {
            if (isDownloading) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在下载中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isDownloading = true;
        }

        try {
            downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("电视直播 更新");
            request.setDescription("正在下载新版本...");
            request.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE
            );
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                );
            } else {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME
                );
            }
            request.allowScanningByMediaScanner();

            downloadId = downloadManager.enqueue(request);
            registerDownloadCompleteReceiver();

            Toast.makeText(context, "开始下载，通知栏可查看进度", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            // 🟢【修复2】下载失败释放锁
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ====================================================================
    // 4. 注册下载完成广播
    // ====================================================================
    private void registerDownloadCompleteReceiver() {
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    installApk();
                    unregisterDownloadCompleteReceiver();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            context.registerReceiver(downloadCompleteReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====================================================================
    // 5. 安装 APK
    // ====================================================================
    private void installApk() {
        try {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = downloadManager.query(query);

            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String uriString = cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    );
                    
                    // 🟢 增加判空防止 uri 为空时崩溃
                    if (uriString != null && !uriString.isEmpty()) {
                        Uri apkUri = Uri.parse(uriString);
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                        context.startActivity(installIntent);
                    } else {
                        Toast.makeText(context, "下载文件丢失，请重新下载", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show();
                }
                cursor.close();
            } else {
                Toast.makeText(context, "未找到下载文件", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(context, "安装失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            // 🟢【修复2】下载完成释放锁
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
        }
    }

    // ====================================================================
    // 6. 释放资源
    // ====================================================================
    public void release() {
        unregisterDownloadCompleteReceiver();
    }
}
