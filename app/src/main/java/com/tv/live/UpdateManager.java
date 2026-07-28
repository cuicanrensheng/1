package com.tv.live;
import android.util.Log;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用更新管理器
 */
public class UpdateManager {
    // 优先从仓库的 update.json 获取完整更新信息（包含详细更新日志）
    private static final String UPDATE_JSON_URL = "https://ghproxy.com/https://raw.githubusercontent.com/cuicanrensheng/1/main/update.json";
    // 备用：GitHub Releases API（用于获取下载链接）
    private static final String RELEASES_API_URL = "https://api.github.com/repos/cuicanrensheng/1/releases/latest";
    private static final String APK_FILE_NAME = "tv_live_update.apk";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static boolean isChecking = false;
    private static boolean isDownloading = false;

    private final Context context;
    private final SharedPreferences sp; 
    private DownloadManager downloadManager;
    private long downloadId = -1;
    private BroadcastReceiver downloadCompleteReceiver;

    public UpdateManager(Context context) {
        this.context = context;
        this.sp = context.getSharedPreferences("app_update", Context.MODE_PRIVATE);
    }

    // 保存更新日志到本地
    public void saveUpdateMessage(String message) {
        sp.edit().putString("update_message", message).apply();
    }

    // 读取已保存的更新日志
    public String getUpdateMessage() {
        return sp.getString("update_message", "暂无更新内容");
    }

    public void checkUpdate() {
        synchronized (UpdateManager.class) {
            if (isChecking) {
                MAIN_HANDLER.post(() -> Toast.makeText(context, "正在检查更新中，请稍后...", Toast.LENGTH_SHORT).show());
                return;
            }
            isChecking = true;
        }

        new Thread(() -> {
            try {
                // ==========================================================
                // 步骤1：优先从 update.json 获取完整的版本信息和更新日志
                // ==========================================================
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
                final int latestVersionCode = json.optInt("versionCode", 0);
                String latestVersionName = json.optString("versionName", "未知");
                String updateMessage = json.optString("message", "暂无更新内容");
                String downloadUrl = json.optString("downloadUrl", "");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);

                // ==========================================================
                // 步骤2：如果 update.json 没有下载链接，备用从 Releases API 获取
                // ==========================================================
                if (downloadUrl.isEmpty()) {
                    try {
                        URL releasesUrl = new URL(RELEASES_API_URL);
                        HttpURLConnection releasesConn = (HttpURLConnection) releasesUrl.openConnection();
                        releasesConn.setRequestMethod("GET");
                        releasesConn.setConnectTimeout(8000);
                        releasesConn.setReadTimeout(8000);

                        if (releasesConn.getResponseCode() == 200) {
                            BufferedReader r2 = new BufferedReader(new InputStreamReader(releasesConn.getInputStream()));
                            StringBuilder sb2 = new StringBuilder();
                            String l2;
                            while ((l2 = r2.readLine()) != null) sb2.append(l2);
                            r2.close();
                            releasesConn.getInputStream().close();
                            releasesConn.disconnect();

                            JSONObject relJson = new JSONObject(sb2.toString());
                            if (relJson.has("assets")) {
                                JSONArray assets = relJson.getJSONArray("assets");
                                for (int i = 0; i < assets.length(); i++) {
                                    JSONObject asset = assets.getJSONObject(i);
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadUrl = asset.getString("browser_download_url");
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Log.w("UpdateManager", "从Releases获取下载链接失败，继续使用已有信息", ex);
                    }
                }

                // 保存更新日志
                saveUpdateMessage(updateMessage);

                if (downloadUrl.isEmpty()) {
                    MAIN_HANDLER.post(() -> {
                        synchronized (UpdateManager.class) {
                            isChecking = false;
                        }
                        Toast.makeText(context, "最新版本未提供下载链接", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                if (latestVersionCode == 0) {
                    MAIN_HANDLER.post(() -> {
                        synchronized (UpdateManager.class) {
                            isChecking = false;
                        }
                        Toast.makeText(context, "获取版本信息失败", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

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
                final String finalUpdateMessage = updateMessage;
                final boolean finalForceUpdate = forceUpdate;

                MAIN_HANDLER.post(() -> {
                    synchronized (UpdateManager.class) {
                        isChecking = false;
                    }
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
                                finalUpdateMessage,
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
                    Toast.makeText(context, "检查更新失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showUpdateDialog(String currentVersion, String latestVersion,
                                   String updateMessage, String downloadUrl,
                                   boolean forceUpdate) {
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
        }

        String message = "📱 发现新版本！\n\n"
                + "当前版本：" + currentVersion + "\n"
                + "最新版本：" + latestVersion + "\n\n"
                + "━━━━━━ 更新内容 ━━━━━━\n"
                + updateMessage;

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

    // ============================================================
    // 下载阶段：保持不变，使用私有目录（无需存储权限）
    // ============================================================
    private void startDownload(String downloadUrl) {
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
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 🟢【关键修复】针对 Android 13+ 下载广播的安全注册
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
        // 🔧 修复：使用 ContextCompat.registerReceiver 并传递 ContextCompat.RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(context, downloadCompleteReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterDownloadCompleteReceiver() {
        if (downloadCompleteReceiver != null) {
            try {
                context.unregisterReceiver(downloadCompleteReceiver);
                downloadCompleteReceiver = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ============================================================
    // 安装阶段：复制到公共 Download 目录，再用公共 Uri 安装
    // ============================================================
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
                    if (uriString != null && !uriString.isEmpty()) {
                        Uri privateUri = Uri.parse(uriString); // 私有目录的 Uri

                        // 🟢 复制到公共 Download 目录（Android 10+ 用 MediaStore，低版本直接使用）
                        Uri publicUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            publicUri = copyToPublicDownload(privateUri);
                            if (publicUri == null) {
                                // 复制失败，回退到私有 Uri，仍可安装
                                publicUri = privateUri;
                            }
                        } else {
                            // Android 9 及以下：直接使用原有路径（就是公共目录）
                            publicUri = privateUri;
                        }

                        // 使用公共 Uri 安装（用户可在文件管理器看到该文件）
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(publicUri, "application/vnd.android.package-archive");
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
            synchronized (UpdateManager.class) {
                isDownloading = false;
            }
        }
    }

    // 🟢 新增：使用 MediaStore 将 APK 从私有目录复制到公共 Download 目录
    private Uri copyToPublicDownload(Uri privateUri) {
        // ✅【关键修复】添加 API 版本判断，消除 Lint NewApi Error
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w("UpdateManager", "当前 Android 版本低于 10，无法使用 MediaStore 复制到公共目录");
            return null;
        }

        try {
            // 1. 准备公共 Download 目录的 ContentValues
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, APK_FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            // 2. 创建公共文件并获取 Uri
            Uri externalUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri publicUri = context.getContentResolver().insert(externalUri, values);
            if (publicUri == null) {
                return null;
            }

            // 3. 打开输入流（私有文件）和输出流（公共文件）
            try (InputStream inputStream = context.getContentResolver().openInputStream(privateUri);
                 OutputStream outputStream = context.getContentResolver().openOutputStream(publicUri)) {

                if (inputStream == null || outputStream == null) {
                    return null;
                }

                // 4. 复制文件
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.flush();
                return publicUri;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void release() {
        unregisterDownloadCompleteReceiver();
    }
}