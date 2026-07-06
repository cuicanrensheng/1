package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.tv.live.manager.TvRemoteManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 设置页面 Activity
 * 已修复遥控器焦点移动、触摸点击高亮跟随以及点击日志卡顿的问题
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode;
    private TextView tv_renderer_type;
    private TextView tv_redirect_setting;
    private TextView tv_boot_status;
    // 🟢【新增】频道线路选择文字显示
    private TextView tv_channel_line;
    // ====================== 配置相关 ======================
    private SharedPreferences sp;
    // ====================================================================
    // 遥控器统一管理器
    // ====================================================================
    private TvRemoteManager remoteManager;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;
    // ====================================================================
    // 管理器相关
    // ====================================================================
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;
    // ====================== SP Key 常量 ======================
    private static final String KEY_CUSTOM_LIVE = "custom_live_url";
    private static final String KEY_CUSTOM_EPG = "custom_epg_url";
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    // 🟢【新增】频道线路选择的 SP 存储键
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    // ====================================================================
    // 全局日志系统（仅保留播放日志，操作日志已移除）
    // ====================================================================
    // 🟢【性能优化1】改为 StringBuffer + 加锁，彻底解决多线程写入崩溃
    public static volatile StringBuffer PLAY_LOG = new StringBuffer();
    // 🟢【修改】日志截取改为最近 100 行
    private static final int MAX_LOG_LINES = 100; 

    public static void log(String msg) {
        LogManager.log(msg);
        if (PLAY_LOG == null) {
            PLAY_LOG = new StringBuffer();
        }
        synchronized (PLAY_LOG) {
            PLAY_LOG.append(msg).append("\n");
        }
    }

    // 🟢【性能优化3】增加防抖机制，避免快速按键时 post 任务堆积
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable focusUpdateRunnable;

    // ====================== onCreate ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            applyFullScreen();
        } catch (Exception e) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Exception e) { }
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initRedirectDefaultConfig();

        sw_boot = findViewById(R.id.sw_boot);
        sw_epg = findViewById(R.id.sw_epg);
        sw_auto_update = findViewById(R.id.sw_auto_update);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_num_channel = findViewById(R.id.sw_num_channel);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_custom_source = findViewById(R.id.tv_custom_source);
        tv_custom_epg = findViewById(R.id.tv_custom_epg);
        tv_multi_source = findViewById(R.id.tv_multi_source);
        tv_multi_epg = findViewById(R.id.tv_multi_epg);
        tv_qr_code = findViewById(R.id.tv_qr_code);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        initSettingsItemList();
        initRemoteManager();
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());

        // ============================================================
        // 🟢【新增】初始化频道线路选择控件
        // ============================================================
        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        tv_channel_line.setText(getLineName(currentLineIndex));
        findViewById(R.id.item_channel_line).setOnClickListener(v -> showChannelLineDialog());
        // ============================================================

        // 开机自启
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean isChecked = !sw_boot.isChecked();
            sw_boot.setChecked(isChecked);
            bootStartManager.toggleBoot(isChecked, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });
        // 节目单开关
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 自动更新源
        sw_auto_update.setChecked(sp.getBoolean("auto_update_source", true));
        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean isChecked = !sw_auto_update.isChecked();
            sw_auto_update.setChecked(isChecked);
            sp.edit().putBoolean("auto_update_source", isChecked).apply();
            if (isChecked) {
                autoUpdateManager.setAutoUpdateAlarm();
            } else {
                autoUpdateManager.cancelAutoUpdateAlarm();
            }
            Toast.makeText(this, "自动更新源" + (isChecked ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        if (sp.getBoolean("auto_update_source", true)) {
            autoUpdateManager.setAutoUpdateAlarm();
        }
        // 换台反转
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 数字选台
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        // 画中画
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        // 解码器选择
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
        });
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> {
            showRendererModeDialog();
        });
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> {
            showRedirectConfigDialog();
        });
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
        });
        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
    }

    // 🟢【新增】获取线路名称的辅助方法
    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    // 🟢【新增】显示频道线路选择弹窗
    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        Channel currentChannel = playerManager.getCurrentChannel();

        // 如果当前没有播放频道，提示用户先播放频道
        if (currentChannel == null) {
            Toast.makeText(this, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }

        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        List<String> lineList = new ArrayList<>();
        lineList.add("主源");
        // 动态生成备用源列表
        for (int i = 1; i <= currentChannel.getBackupUrls().size(); i++) {
            lineList.add("源" + i);
        }

        String[] lineArray = lineList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("频道线路选择")
                .setSingleChoiceItems(lineArray, currentLineIndex, (dialog, which) -> {
                    sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
                    tv_channel_line.setText(lineArray[which]);
                    // 通知播放器刷新当前频道
                    sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                    dialog.dismiss();
                    Toast.makeText(this, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT,5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL,false);
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
        }
    }

    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";
        
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain?"开":"关").append(" | ");
        sb.append("跨协议：").append(crossProto?"开":"关").append("\n");
        sb.append("携带请求头：").append(followHeader?"开":"关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl?"开":"关").append(" | ");
        sb.append("授权令牌：").append(sendCookie?"开":"关").append(" | ");
        sb.append("UA：").append(uaLabel);
        tv_redirect_setting.setText(sb.toString());
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
        }
    }

    // ========================== 🟢 修复点：补全触摸点击支持，并优化彻底去除了导致背景不动的竞态代码 ==========================
    private void initSettingsItemList() {
        settingsItemList.clear();
        settingsItemList.add(findViewById(R.id.item_boot));
        settingsItemList.add(findViewById(R.id.item_epg));
        settingsItemList.add(findViewById(R.id.item_auto_update));
        settingsItemList.add(findViewById(R.id.item_reverse));
        settingsItemList.add(findViewById(R.id.item_num_channel));
        settingsItemList.add(findViewById(R.id.item_pip));
        settingsItemList.add(findViewById(R.id.item_decoder));
        settingsItemList.add(findViewById(R.id.item_renderer));
        settingsItemList.add(findViewById(R.id.item_redirect));
        settingsItemList.add(findViewById(R.id.tv_screen_ratio));
        settingsItemList.add(findViewById(R.id.tv_custom_source));
        settingsItemList.add(findViewById(R.id.tv_custom_epg));
        settingsItemList.add(findViewById(R.id.tv_multi_source));
        settingsItemList.add(findViewById(R.id.tv_multi_epg));
        settingsItemList.add(findViewById(R.id.tv_qr_code));
        // 🟢 新增：线路选择加入焦点列表
        settingsItemList.add(findViewById(R.id.item_channel_line));
        settingsItemList.add(findViewById(R.id.log_viewer));
        // 操作日志按钮已移除
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
                item.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus && remoteManager != null) {
                        int currentPos = remoteManager.getSettingsFocusPosition();
                        if (currentPos != position) {
                            remoteManager.setSettingsFocusPosition(position);
                            // 🟢 补上这行：让触摸点击或外部设备焦点也能立刻刷新样式
                            updateSettingsFocus();
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
                updateSettingsFocus();
            }
            @Override
            public void onSettingsMoveDown() {
                updateSettingsFocus();
            }
            @Override
            public void onSettingsConfirm() {
                int position = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(position);
            }
            @Override
            public boolean onSettingsBack() {
                finish();
                return true;
            }
            @Override
            public void onSettingsMenu() {
                finish();
            }
            @Override
            public void onSettingsFocusChanged(int position) {
                updateSettingsFocus();
            }
            @Override
            public boolean onPipBack() { return false; }
            @Override
            public void onRequestPlayFocus() {}
            @Override
            public void onChannelNumberSelected(int channelIndex) {}
            @Override
            public void onShowChannelNumber(String number) {}
            @Override
            public void onHideChannelNumber() {}
        });
        updateSettingsFocus();
    }

    private void initListeners() {
        tv_screen_ratio.setOnClickListener(v -> {
            showRatioDialog();
        });
        tv_custom_source.setOnClickListener(v -> {
            showInputDialog("自定义订阅源", "请输入直播源地址", KEY_CUSTOM_LIVE);
        });
        tv_custom_epg.setOnClickListener(v -> {
            showInputDialog("自定义节目单", "请输入EPG地址", KEY_CUSTOM_EPG);
        });
        tv_multi_source.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("直播源历史", "live_history");
        });
        tv_multi_epg.setOnClickListener(v -> {
            sourceDialogManager.showHistoryDialog("节目单历史", "epg_history");
        });
        tv_qr_code.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========================== 🟢 修复焦点更新算法：取消复杂的 isFocused 分支，强制设置颜色，完美解决高亮无法移动的问题 ==========================
    // 🟢【性能优化3】增加防抖机制，避免快速按键时 post 任务堆积
    private void updateSettingsFocus() {
        if (remoteManager == null) return;
        int selectedPosition = remoteManager.getSettingsFocusPosition();
        if (selectedPosition < 0 || selectedPosition >= settingsItemList.size()) return;

        View target = settingsItemList.get(selectedPosition);
        if (target == null) return;

        // 1. 统一处理所有Item的背景色和文本色，消除旧代码里因为 isFocused() 引发的竞争和错乱
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }

        // 2. 防抖处理：取消之前可能堆积的滚动/聚焦任务
        if (focusUpdateRunnable != null) {
            mainHandler.removeCallbacks(focusUpdateRunnable);
        }
        focusUpdateRunnable = () -> {
            scrollToView(target);
            target.requestFocus();
        };
        // 立即执行（防抖让之前的堆积取消，只执行最新一次）
        mainHandler.post(focusUpdateRunnable);
    }

    private void setItemStyle(View item, String textColor, int typefaceStyle, int bgColor) {
        item.setBackgroundColor(bgColor);
        if (item instanceof TextView) {
            TextView tv = (TextView) item;
            tv.setTextColor(Color.parseColor(textColor));
            tv.setTypeface(null, typefaceStyle);
        } else if (item instanceof ViewGroup) {
            TextView tv = findFirstTextView((ViewGroup) item);
            if (tv != null) {
                tv.setTextColor(Color.parseColor(textColor));
                tv.setTypeface(null, typefaceStyle);
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

    // ========================== 🟢 瞬间跳转，避免动画队列堆积卡死 ==========================
    private void scrollToView(View view) {
        if (scrollView == null || view == null) return;
        int viewTop = view.getTop();
        int viewBottom = view.getBottom();
        int scrollViewHeight = scrollView.getHeight();
        int currScroll = scrollView.getScrollY();

        if (viewTop < currScroll) {
            scrollView.scrollTo(0, Math.max(0, viewTop - 50));
        } else if (viewBottom > currScroll + scrollViewHeight) {
            scrollView.scrollTo(0, viewBottom - scrollViewHeight + 50);
        }
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item == null) return;
        item.performClick();
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        String currentMode = sp.getString("decoder_mode", "auto");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    sp.edit().putString("decoder_mode", selectedMode).apply();
                    updateDecoderModeText(selectedMode);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            case "auto": default: tv_decoder_mode.setText("自动"); break;
        }
    }

    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        String currentMode = sp.getString("renderer_type", "surface");
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modeValues[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("渲染方式选择")
                .setSingleChoiceItems(modes, checkedItem, (d, which) -> {
                    String selectedMode = modeValues[which];
                    sp.edit().putString("renderer_type", selectedMode).apply();
                    updateRendererModeText(selectedMode);
                    sendBroadcast(new Intent("com.tv.live.RENDERER_TYPE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture":
                tv_renderer_type.setText("TextureView");
                break;
            case "surface":
            default:
                tv_renderer_type.setText("SurfaceView");
                break;
        }
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT,5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN,true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL,true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS,true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL,false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = { sp.getString(KEY_USER_AGENT_MODE, "exo") };
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);

        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);
        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC播放器");
        
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "VLC播放器"};
            final String[] uaValues = {"exo", "vlc"};
            int checkedItem = 0;
            for (int i = 0; i < uaValues.length; i++) {
                if (uaValues[i].equals(currentUaMode[0])) {
                    checkedItem = i;
                    break;
                }
            }
            new AlertDialog.Builder(this)
                .setTitle("UA切换")
                .setSingleChoiceItems(uaOptions, checkedItem, (d, which) -> {
                    currentUaMode[0] = uaValues[which];
                    tvUserAgentStatus.setText(uaOptions[which]);
                    d.dismiss();
                }).show();
        });

        new AlertDialog.Builder(this)
                .setTitle("HTTP重定向网络配置")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String maxStr = etMax.getText().toString().trim();
                    int newMax = 5;
                    if (!TextUtils.isEmpty(maxStr)) {
                        try {
                            newMax = Integer.parseInt(maxStr);
                            if(newMax < 1) newMax = 1;
                            if(newMax > 20) newMax = 20;
                        }catch (Exception ignored){
                            newMax =5;
                        }
                    }
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt(KEY_REDIRECT_MAX_COUNT, newMax);
                    editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, swCrossDomain.isChecked());
                    editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, swCrossProto.isChecked());
                    editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, swFollowHeader.isChecked());
                    editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, swIgnoreSsl.isChecked());
                    editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, swSendCookie.isChecked());
                    editor.putString(KEY_USER_AGENT_MODE, currentUaMode[0]);
                    editor.apply();
                    updateRedirectSettingText();
                    Toast.makeText(this, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInputDialog(String title, String hint, String key) {
        EditText ed = new EditText(this);
        ed.setHint(hint);
        ed.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ed)
                .setPositiveButton("确定", (d, w) -> {
                    String url = ed.getText().toString().trim();
                    if (!url.isEmpty()) {
                        sp.edit().putString(key, url).apply();
                        SourceManager sourceManager = new SourceManager(this,
                                key.contains("live") ? "live_history" : "epg_history");
                        sourceManager.addSource(url.substring(0, Math.min(10, url.length())) + "...", url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========================== 🟢 播放日志（保留） ==========================
    private void showLogDialog() {
        new Thread(() -> {
            final String logContent;
            if (PLAY_LOG == null || PLAY_LOG.length() == 0) {
                logContent = "暂无日志内容，请先播放一个频道再查看。";
            } else {
                String originalLog;
                synchronized (PLAY_LOG) {
                    originalLog = PLAY_LOG.toString();
                }
                String[] lines = originalLog.split("\n");
                // 🟢【修改】限制日志行数，只保留最近 100 行，防止弹窗卡死
                if (lines.length > MAX_LOG_LINES) {
                    List<String> subList = new ArrayList<>();
                    for (int i = lines.length - MAX_LOG_LINES; i < lines.length; i++) {
                        subList.add(lines[i]);
                    }
                    lines = subList.toArray(new String[0]);
                }
                
                List<String> lagLines = new ArrayList<>();
                StringBuilder fullReverseLog = new StringBuilder();
                String[] lagKeywords = {
                        "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                        "buffer underflow", "frame drop", 
                        "buffering", "stall", "delay", "timeout", "decoder error",
                        "Forbidden", "访问拒绝", "跳转失败", 
                        "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
                };

                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("开始播放") || (line.startsWith("第") && line.contains("重定向到"))) {
                        fullReverseLog.append(line).append("\n");
                        continue;
                    }

                    boolean hitLag = false;
                    for (String kw : lagKeywords) {
                        if (line.contains(kw)) {
                            hitLag = true;
                            break;
                        }
                    }
                    if (hitLag && !lagLines.contains(line)) {
                        lagLines.add(line);
                    }
                    fullReverseLog.append(line).append("\n");
                }

                StringBuilder fullContent = new StringBuilder();
                fullContent.append("========== 卡顿原因分析汇总 ==========\n");
                if (!lagLines.isEmpty()) {
                    for (String lagItem : lagLines) {
                        fullContent.append(lagItem).append("\n");
                    }
                } else {
                    fullContent.append("未检测到卡顿相关日志\n");
                }
                fullContent.append("\n========== 完整播放日志 ==========\n");
                fullContent.append(fullReverseLog);
                logContent = fullContent.toString();
            }

            runOnUiThread(() -> renderPlayLogDialog(logContent));
        }).start();
    }

    private void renderPlayLogDialog(String logContent) {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        SpannableString spLog = new SpannableString(logContent);
        String[] lagKeywords = {
                "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                "buffer underflow", "frame drop", "404",
                "buffering", "stall", "delay", "timeout", "decoder error",
                "Forbidden", "访问拒绝", "跳转失败", 
                "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
        };
        String totalText = logContent;
        for (String key : lagKeywords) {
            int searchIndex = 0;
            while ((searchIndex = totalText.indexOf(key, searchIndex)) != -1) {
                spLog.setSpan(
                        new ForegroundColorSpan(Color.RED),
                        searchIndex,
                        searchIndex + key.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                searchIndex += key.length();
            }
        }
        tv.setText(spLog);
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📄 解析 & 播放日志（卡顿分析）");
        builder.setView(scrollView);
        builder.setPositiveButton("关闭", null);
        builder.setNeutralButton("清空日志", (dialog, which) -> {
            LogManager.clearPlayLog();
            if (PLAY_LOG != null) {
                synchronized (PLAY_LOG) {
                    PLAY_LOG.setLength(0);
                }
            }
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                applyFullScreen();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
                layoutParams.dimAmount = 0f;
                getWindow().setAttributes(layoutParams);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } catch (Exception e) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServerManager != null) {
            webServerManager.stop();
        }
        if (updateManager != null) {
            updateManager.release();
        }
        remoteManager = null;
        settingsItemList.clear();
        settingsItemList = null;
    }
}
