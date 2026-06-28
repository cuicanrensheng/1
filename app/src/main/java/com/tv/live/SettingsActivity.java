package com.tv.live;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置页面 Activity
 * 【功能】解析日志按钮+卡顿自动分析，修复全部10处编译报错
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private Switch sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel;
    private Switch sw_pip;
    private TextView tv_decoder_mode;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_boot_status;
    private SharedPreferences sp;
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView; // 页面全局滚动控件，区分弹窗局部sv
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
    // 全局播放日志缓冲区
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    // 全局操作日志缓冲区
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();

    // 打印播放日志（TVPlayerManager统一调用）
    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) PLAY_LOG = new StringBuilder();
        PLAY_LOG.append(msg).append("\n");
    }

    // 打印操作日志
    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        if (OPERATION_LOG == null) OPERATION_LOG = new StringBuilder();
        OPERATION_LOG.append(msg).append("\n");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全面屏适配
        try {
            applyFullScreen();
        } catch (Exception e) {
            logOperation("全屏适配失败：" + e.getMessage());
        }
        // 刘海屏适配
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) {
            logOperation("刘海屏适配异常");
        }
        // 清除弹窗变暗
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {
            logOperation("窗口透明度设置异常");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        // 绑定开关控件
        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        // 文本设置项
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        // 业务管理器初始化
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        initSettingsItemList();
        initRemoteManager();

        // 日志按钮绑定：卡顿自动分析日志弹窗
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialog());

        // 开机自启
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean checked = !sw_boot.isChecked();
            sw_boot.setChecked(checked);
            bootStartManager.toggleBoot(checked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });

        // EPG开关 【修复putBoolean少传布尔值】
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean c = !sw_epg.isChecked();
            sw_epg.setChecked(c);
            sp.edit().putBoolean("epg_enable", c).apply();
            logOperation("【设置】节目单" + (c ? "开启" : "关闭"));
            Toast.makeText(this, "节目已" + (c ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 自动更新源 【修复putBoolean少传布尔值】
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean c = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(c);
            sp.edit().putBoolean("auto_update_source", c).apply();
            if (c) autoUpdateManager.setAutoUpdateAlarm();
            else autoUpdateManager.cancelAutoUpdateAlarm();
            logOperation("【设置】自动更新源：" + c);
        });

        // 频道反转 【修复putBoolean少传布尔值】
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean c = !sw_reverse.isChecked();
            sw_reverse.setChecked(c);
            sp.edit().putBoolean("channel_reverse", c).apply();
            logOperation("【设置】频道切换反转：" + c);
        });

        // 数字选台 【修复putBoolean少传布尔值】
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean c = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(c);
            sp.edit().putBoolean("number_channel_enable", c).apply();
            logOperation("【设置】数字选台：" + c);
        });

        // 画中画 【修复putBoolean少传布尔值】
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean c = !sw_pip.isChecked();
            sw_pip.setChecked(c);
            sp.edit().putBoolean("pip_enable", c).apply();
            logOperation("【设置】画中画：" + c);
        });

        // 解码器模式
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> showDecoderModeDialog());
        findViewById(R.id.item_check_update).setOnClickListener(v -> updateManager.checkUpdate());

        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
        logOperation("【设置】页面打开");
    }

    /**
     * 【核心播放日志弹窗 修复scroll/tv变量重名】
     */
    private void showLogDialog() {
        // 弹窗局部滚动控件，改名sv 不与全局scrollView冲突
        ScrollView sv = new ScrollView(this);
        // 弹窗文本控件改名logTv，不冲突
        TextView logTv = new TextView(this);
        logTv.setTextSize(12);
        logTv.setPadding(40, 40, 40, 40);
        logTv.setTextColor(Color.BLACK);

        String rawLog = PLAY_LOG == null ? "" : PLAY_LOG.toString();
        String[] lines = rawLog.split("\n");
        StringBuilder content = new StringBuilder();

        // 自动分析卡顿原因
        List<String> stallReasons = analyzeStallReasons(lines);
        if (!stallReasons.isEmpty()) {
            content.append("==================== 直播卡顿自动分析报告 ====================\n");
            for (String r : stallReasons) {
                content.append("★ ").append(r).append("\n");
            }
            content.append("================================================\n\n");
        } else if (rawLog.trim().length() > 0) {
            content.append("==================== 未检测到明显卡顿 ====================\n\n");
        }

        // 日志倒序输出
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (!line.trim().isEmpty()) {
                content.append(line).append("\n");
            }
        }

        if (content.length() == 0) {
            logTv.setText("暂无播放日志，请先播放直播源产生日志");
        } else {
            logTv.setText(content.toString());
        }
        sv.addView(logTv);

        // 弹窗使用sv局部变量，消除找不到符号错误
        new AlertDialog.Builder(this)
                .setTitle("解析&播放日志（卡顿自动分析）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空全部播放日志", (dialog, w) -> {
                    LogManager.clearPlayLog();
                    PLAY_LOG.setLength(0);
                    Toast.makeText(this, "播放日志已清空", Toast.LENGTH_SHORT).show();
                    logOperation("【设置】清空播放日志");
                })
                .show();
    }

    /**
     * 卡顿分析核心逻辑
     */
    private List<String> analyzeStallReasons(String[] logLines) {
        List<String> result = new ArrayList<>();
        int bufferCount = 0;
        long totalStallMs = 0;
        boolean useHardDecoder = false;
        boolean autoSwitchSoft = false;
        boolean netError = false;
        boolean highBitrate = false;

        Pattern patBuffer = Pattern.compile("【播放器】开始缓冲");
        Pattern patStallTime = Pattern.compile("卡顿结束，时长：(\\d+)ms");
        Pattern patHard = Pattern.compile("硬解模式");
        Pattern patAutoSoft = Pattern.compile("硬解卡顿，自动切换到系统软解");
        Pattern patNet = Pattern.compile("播放异常|网络|超时");
        Pattern patBit = Pattern.compile("(\\d+\\.\\d+) Mbps");

        for (String line : logLines) {
            if (line == null || line.trim().length() == 0) continue;
            Matcher mBuf = patBuffer.matcher(line);
            if (mBuf.find()) bufferCount++;

            Matcher mStall = patStallTime.matcher(line);
            if (mStall.find()) {
                long t = Long.parseLong(mStall.group(1));
                totalStallMs += t;
            }

            if (patHard.matcher(line).find()) useHardDecoder = true;
            if (patAutoSoft.matcher(line).find()) autoSwitchSoft = true;
            if (patNet.matcher(line).find()) netError = true;

            Matcher mBit = patBit.matcher(line);
            if (mBit.find()) {
                float br = Float.parseFloat(mBit.group(1));
                if (br >= 6.0f) highBitrate = true;
            }
        }

        // 分级判断卡顿根源
        if (autoSwitchSoft) {
            result.add("硬解码器与当前直播源不兼容，建议切换软解模式");
        } else if (netError || bufferCount > 5) {
            result.add("网络波动/丢包/延迟过高，更换WiFi/5G或低码流源");
        } else if (highBitrate) {
            result.add("直播源码率过高，本地带宽不足造成持续缓冲");
        } else if (totalStallMs > 10000) {
            result.add("直播源服务器负载高/分片损坏，源本身不稳定");
        }
        return result;
    }

    /** 操作日志弹窗 统一局部变量sv/logTv */
    private void showOperationLogDialog() {
        ScrollView sv = new ScrollView(this);
        TextView logTv = new TextView(this);
        logTv.setTextSize(12);
        logTv.setPadding(40, 40, 40, 40);
        logTv.setTextColor(Color.BLACK);

        StringBuilder opBuf = OPERATION_LOG == null ? new StringBuilder() : new StringBuilder(OPERATION_LOG);
        String[] lines = opBuf.toString().split("\n");
        StringBuilder rev = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isEmpty()) rev.append(lines[i]).append("\n");
        }
        logTv.setText(rev.toString());
        sv.addView(logTv);

        new AlertDialog.Builder(this)
                .setTitle("操作日志")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空操作日志", (d, w) -> {
                    LogManager.clearOperationLog();
                    OPERATION_LOG.setLength(0);
                    Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void applyFullScreen() {
        try {
            int uiOpt = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOpt);
        } catch (Exception e) {
            logOperation("全屏UI设置失败");
        }
    }

    // 初始化遥控器焦点列表
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

        // 清理空控件
        for (int i = settingsItemList.size() - 1; i >= 0; i--) {
            if (settingsItemList.get(i) == null) settingsItemList.remove(i);
        }

        // 焦点监听适配遥控器+触摸
        for (View item : settingsItemList) {
            if (item == null) continue;
            item.setFocusableInTouchMode(true);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && remoteManager != null) {
                    int currPos = settingsItemList.indexOf(v);
                    int remotePos = remoteManager.getSettingsFocusPosition();
                    if (currPos != remotePos) {
                        remoteManager.setSettingsFocusPosition(currPos);
                        updateSettingsFocus();
                        logOperation("【设置】焦点移动至第" + (currPos + 1) + "项");
                    }
                }
            });
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

            // 修复不存在moveUp/moveDown，调用接口标准方法
            @Override
            public void onSettingsMoveUp() {
                remoteManager.onSettingsMoveUp();
                updateSettingsFocus();
            }
            @Override
            public void onSettingsMoveDown() {
                remoteManager.onSettingsMoveDown();
                updateSettingsFocus();
            }
            @Override
            public void onSettingsConfirm() {
                int pos = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(pos);
            }
            @Override
            public boolean onSettingsBack() {
                logOperation("【设置】按返回关闭页面");
                finish();
                return true;
            }
            @Override public void onSettingsMenu() { finish(); }
            @Override public void onSettingsFocusChanged(int position) { updateSettingsFocus(); }
            @Override public boolean onPipBack() { return false; }
            @Override public void onRequestPlayFocus() {}
            @Override public void onChannelNumberSelected(int channelIndex) {}
            @Override public void onShowChannelNumber(String number) {}
            @Override public void onHideChannelNumber() {}
        });
        updateSettingsFocus();
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());
        tv_custom_source.setOnClickListener(v -> showInputDialog("自定义直播源", "输入M3U8地址", KEY_CUSTOM_LIVE));
        tv_custom_epg.setOnClickListener(v -> showInputDialog("自定义EPG", "输入节目单地址", KEY_CUSTOM_EPG));
        tv_multi_source.setOnClickListener(v -> sourceDialogManager.showHistoryDialog("直播源历史", "live_history"));
        tv_multi_epg.setOnClickListener(v -> sourceDialogManager.showHistoryDialog("EPG历史", "epg_history"));
        tv_qr_code.setOnClickListener(v -> qrCodeManager.showQRCodeDialog(currentWebUrl));
    }

    private void updateDecoderModeText(String mode) {
        if ("hard".equals(mode)) {
            tv_decoder_mode.setText("硬解");
        } else if ("soft".equals(mode)) {
            tv_decoder_mode.setText("软解（兼容性好）");
        } else {
            tv_decoder_mode.setText("自动");
        }
    }

    private void showDecoderModeDialog() {
        final String[] modeNames = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeVals = {"auto", "hard", "soft"};
        String currMode = sp.getString("decoder_mode", "auto");
        int selectIdx = 0;
        for (int i = 0; i < modeVals.length; i++) {
            if (modeVals[i].equals(currMode)) selectIdx = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modeNames, selectIdx, (dialog, which) -> {
                    String selMode = modeVals[which];
                    sp.edit().putString("decoder_mode", selMode).apply();
                    updateDecoderModeText(selMode);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    logOperation("【设置】切换解码器：" + modeNames[which]);
                    Toast.makeText(this, "切换完成，重新播放生效", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }).show();
    }

    private void showRatioDialog() {
        String[] ratioArr = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("画面比例")
                .setItems(ratioArr, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratioArr[w]).apply();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showInputDialog(String title, String hint, String spKey) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(sp.getString(spKey, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(editText)
                .setPositiveButton("确定", (d, w) -> {
                    String url = editText.getText().trim();
                    sp.edit().putString(spKey, url).apply();
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    logOperation("【设置】更新自定义地址：" + url);
                    Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSettingsFocus() {
        int selectedPos = remoteManager.getSettingsFocusPosition();
        logOperation("焦点更新至第" + (selectedPos + 1) + "项");
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPos) {
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

    // 修复变量text不存在错误，参数改为textColor
    private void setItemStyle(View item, String textColor, int fontStyle, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, fontStyle);
        } else if (item instanceof ViewGroup) {
            TextView tv = findFirstTv((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, fontStyle);
            }
        }
    }

    private TextView findFirstTv(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
            else if (child instanceof ViewGroup) {
                TextView res = findFirstTv((ViewGroup) child);
                if (res != null) return res;
            }
        }
        return null;
    }

    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int top = view.getTop();
        int bottom = view.getBottom();
        int scrollH = scrollView.getHeight();
        int currY = scrollView.getScrollY();
        if (top < currY) {
            scrollView.smoothScrollTo(0, top - 50);
        } else if (bottom > currY + scrollH) {
            scrollView.smoothScrollTo(0, bottom - scrollH + 50);
        }
    }

    private void handleSettingsItemClick(int pos) {
        if (pos < 0 || pos >= settingsItemList.size()) return;
        View item = settingsItemList.get(pos);
        if (item != null) item.performClick();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                applyFullScreen();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.dimAmount = 0f;
                getWindow().setAttributes(lp);
            } catch (Exception e) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】页面关闭");
        if (webServerManager != null) webServerManager.stop();
        if (updateManager != null) updateManager.release();
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
    }
}
