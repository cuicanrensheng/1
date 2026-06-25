package com.tv.live;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.manager.TvRemoteManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 *
 * 【功能清单】
 * 1. 开机自启开关（委托给 BootStartManager）
 * 2. 节目单开关
 * 3. 自动更新源（委托给 AutoUpdateManager）
 * 4. 换台反转
 * 5. 数字选台
 * 6. 画中画（后台小窗播放）开关
 * 7. 解码器选择（自动/硬解/软解）
 * 8. 屏幕比例设置
 * 9. 自定义订阅源/节目单
 * 10. 多订阅源/节目单管理（委托给 SourceDialogManager）
 * 11. 扫码添加（委托给 QRCodeManager）
 * 12. 解析&播放日志查看
 * 13. 操作日志查看
 * 14. 检查更新（委托给 UpdateManager）
 *
 * 【2026-06-25 优化：改用 SettingsManager 统一管理设置项】
 * 【修改说明】
 * 原来直接操作 SharedPreferences，现在统一通过 SettingsManager 读写，
 * 和 MainActivity 用同一套设置体系，避免 SP 文件不统一的问题。
 */
public class SettingsActivity extends AppCompatActivity {

    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private Switch sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode;
    private TextView tv_boot_status;

    private SettingsManager settingsManager;

    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;

    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;

    private UpdateManager updateManager;

    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";

    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuilder();
        }
        PLAY_LOG.append(msg).append("\n");
    }

    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        if (OPERATION_LOG == null) {
            OPERATION_LOG = new StringBuilder();
        }
        OPERATION_LOG.append(msg).append("\n");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            applyFullScreen();
        } catch (Exception e) {
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {
        }

        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        settingsManager = SettingsManager.getInstance(this);

        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        bootStartManager = new BootStartManager(this, settingsManager.getSharedPreferences());
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, settingsManager.getSharedPreferences());
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        initSettingsItemList();
        initRemoteManager();

        findViewById(R.id.log_viewer).setOnClickListener(v -> {
            showLogDialog();
        });
        findViewById(R.id.log_operation).setOnClickListener(v -> {
            showOperationLogDialog();
        });

        sw_boot.setChecked(settingsManager.isBootAutoStart());
        bootStartManager.updateBootStatusText(tv_boot_status);
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            settingsManager.setBootAutoStart(isChecked);
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });

        sw_epg.setChecked(settingsManager.isEpgEnabled());
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            settingsManager.setEpgEnabled(isChecked);
            logOperation("【设置】节目单" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_auto_update.setChecked(settingsManager.isAutoUpdateSource());
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            settingsManager.setAutoUpdateSource(isChecked);
            if (isChecked) {
                autoUpdateManager.setAutoUpdateAlarm();
            } else {
                autoUpdateManager.cancelAutoUpdateAlarm();
            }
            logOperation("【设置】自动更新源" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        if (settingsManager.isAutoUpdateSource()) {
            autoUpdateManager.setAutoUpdateAlarm();
        }

        sw_reverse.setChecked(settingsManager.isChannelReverse());
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            settingsManager.setChannelReverse(isChecked);
            logOperation("【设置】换台反转" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_num_channel.setChecked(settingsManager.isNumberChannelEnabled());
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            settingsManager.setNumberChannelEnabled(isChecked);
            logOperation("【设置】数字选台" + (isChecked ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        sw_pip.setChecked(settingsManager.isPipEnabled());
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            settingsManager.setPipEnabled(isChecked);
            logOperation("【设置】画中画（后台小窗播放）" + (isChecked ? "已开启" : "已关闭"));
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        String decoderMode = settingsManager.getDecoderModeString();
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器选择");
        });

        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });

        initListeners();

        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】打开设置页面");
    }

    private void applyFullScreen() {
        try {
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        } catch (Exception e) {
            logOperation("【设置】全面屏适配失败：" + e.getMessage());
        }
    }

    private void initSettingsItemList() {
        settingsItemList.clear();
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(findViewById(R.id.tv_custom_source));
        settingsItemList.add(findViewById(R.id.tv_custom_epg));
        settingsItemList.add(findViewById(R.id.tv_multi_source));
        settingsItemList.add(findViewById(R.id.tv_multi_epg));
        settingsItemList.add(findViewById(R.id.tv_qr_code));
        settingsItemList.add(findViewById(R.id.log_viewer));
        settingsItemList.add(findViewById(R.id.log_operation));
        settingsItemList.add(findViewById(R.id.item_check_update));

        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) {
                settingsItemList.remove(i);
            }
        }

        for (int i = 0; i < settingsItemList.size(); i++) {
            final int position = i;
            View item = settingsItemList.get(i);
            if (item != null) {
                item.setFocusableInTouchMode(true);
                item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus && remoteManager != null) {
                            int currentPos = remoteManager.getSettingsFocusPosition();
                            if (currentPos != position) {
                                remoteManager.setSettingsFocusPosition(position);
                                updateSettingsFocus();
                                logOperation("【设置】焦点移动到第 " + (position + 1) + " 项（点击）");
                            }
                        }
                    }
                });
            }
        }
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.SETTINGS_MODE);
        remoteManager.setSettingsItemCount(settingsItemList.size());
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override public void onPlayChannelUp() {}
            @Override public void onPlayChannelDown() {}
            @Override public void onPlayTogglePanel() {}
            @Override public void onPlayOpenSettings() {}
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() {}
            @Override public void onPanelMoveDown() {}
            @Override public void onPanelMoveLeft() {}
            @Override public void onPanelMoveRight() {}
            @Override public void onPanelConfirm() {}
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}

            @Override
            public void onSettingsMoveUp() {
                int newPos = remoteManager.getSettingsFocusPosition();
                logOperation("【设置遥控】上键 → 移动到第 " + (newPos + 1) + " 项");
                updateSettingsFocus();
            }

            @Override
            public void onSettingsMoveDown() {
                int newPos = remoteManager.getSettingsFocusPosition();
                logOperation("【设置遥控】下键 → 移动到第 " + (newPos + 1) + " 项");
                updateSettingsFocus();
            }

            @Override
            public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }

            @Override
            public boolean onSettingsBack() {
                logOperation("【设置遥控】返回键 → 关闭设置页面");
                finish();
                return true;
            }

            @Override
            public void onSettingsMenu() {
                logOperation("【设置遥控】菜单键 → 关闭设置页面");
                finish();
            }

            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }
        });
        updateSettingsFocus();
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
            logOperation("【设置】打开屏幕比例设置");
        });
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
            logOperation("【设置】打开自定义订阅源");
        });
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
            logOperation("【设置】打开自定义节目单");
        });
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
            logOperation("【设置】打开直播源历史");
        });
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
            logOperation("【设置】打开节目单历史");
        });
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            logOperation("【设置】打开扫码管理");
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        logOperation("【设置遥控】焦点更新 → 第 " + (selectedPosition + 1) + " 项");

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
                item.requestFocus();
                scrollToView(item);
            } else if (item.isFocused()) {
                setItemStyle(item, "#40A9FF", Typeface.NORMAL, Color.TRANSPARENT);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }
    }

    private void setItemStyle(View item, String textColor, int typeface, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typeface);
        } else if (item instanceof ViewGroup) {
            TextView tv = findFirstTextView((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, typeface);
            }
        }
    }

    private TextView findFirstTextView(ViewGroup viewGroup) {
        if (viewGroup == null) return null;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            } else if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();
        if (viewTop < scrollView.getScrollY()) {
            scrollView.smoothScrollTo(0, viewTop - 50);
        } else if (viewBottom > scrollView.getScrollY() + scrollViewHeight) {
            scrollView.smoothScrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;
        item.performClick();
        logOperation("【设置遥控】选中第 " + (position + 1) + " 项");
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    settingsManager.setScreenRatio(ratios[w]);
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（FFmpeg）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = settingsManager.getDecoderModeString();
        int checkedItem = 0;
        for (int i = 0; i < modeValues.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    settingsManager.setDecoderModeString(selectedMode);
                    updateDecoderModeText(selectedMode);
                    logOperation("【设置】解码器选择：" + modes[which]);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard":
                tv_decoder_mode.setText("硬解");
                break;
            case "soft":
                tv_decoder_mode.setText("软解");
                break;
            case "auto":
            default:
                tv_decoder_mode.setText("自动");
                break;
        }
    }

    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        if (KEY_CUSTOM_LIVE.equals(key)) {
            ed.setText(settingsManager.getCustomLiveUrl());
        } else if (KEY_CUSTOM_EPG.equals(key)) {
            ed.setText(settingsManager.getCustomEpgUrl());
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    if (!url.isEmpty()) {
                        if (KEY_CUSTOM_LIVE.equals(key)) {
                            settingsManager.setCustomLiveUrl(url);
                        } else if (KEY_CUSTOM_EPG.equals(key)) {
                            settingsManager.setCustomEpgUrl(url);
                        }
                        SourceManager sourceManager = new SourceManager(this,
                                key.contains("live") ? "live_history" : "epg_history");
                        sourceManager.addSource(url.substring(0, Math.min(10, url.length())) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showOperationLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (OPERATION_LOG == null || OPERATION_LOG.length() == 0) {
            tv.setText("暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。");
        } else {
            String originalLog = OPERATION_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }
        tv.setTextSize(12);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(40, 40, 40, 40);
        scrollView.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("操作日志")
                .setView(scrollView)
                .setPositiveButton("清空", (d, w) -> {
                    LogManager.clearOperationLog();
                    if (OPERATION_LOG != null) {
                        OPERATION_LOG.setLength(0);
                    }
                    logOperation("【设置】操作日志已清空");
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showLogDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
            tv.setText("暂无解析日志。\n\n解析日志会记录直播源的解析、缓冲、播放等详细信息，\n方便排查播放问题。");
        } else {
            String originalLog = PLAY_LOG.toString();
            String[] lines = originalLog.split("\n");
            StringBuilder reversedLog = new StringBuilder();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].trim().isEmpty()) {
                    reversedLog.append(lines[i]).append("\n");
                }
            }
            tv.setText(reversedLog.toString());
        }
        tv.setTextSize(12);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(40, 40, 40, 40);
        scrollView.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("解析&播放日志")
                .setView(scrollView)
                .setPositiveButton("清空", (d, w) -> {
                    LogManager.clearPlayLog();
                    if (PLAY_LOG != null) {
                        PLAY_LOG.setLength(0);
                    }
                    logOperation("【设置】解析日志已清空");
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServerManager != null) {
            webServerManager.stop();
        }
        logOperation("【设置】关闭设置页面");
    }
}
