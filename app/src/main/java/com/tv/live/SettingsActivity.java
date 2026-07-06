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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设置页面 Activity
 * 已完整优化全部卡顿问题
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_custom_source, tv_custom_epg, tv_multi_source, tv_multi_epg, tv_qr_code;
    private TextView tv_decoder_mode;
    private TextView tv_renderer_type;
    private TextView tv_redirect_setting;
    private TextView tv_boot_status;
    private ScrollView scrollView;

    // ====================== 缓存TextView，消除递归查找 ======================
    private final List<TextView> itemTextCache = new ArrayList<>();
    private final List<View> settingsItemList = new ArrayList<>();

    // ====================== SP内存缓存，减少磁盘IO ======================
    private AppSettingCache settingCache;
    private SharedPreferences sp;

    // ====================== 遥控器 ======================
    private TvRemoteManager remoteManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();
    private static final long FOCUS_DEBOUNCE_MS = 80;
    private final AtomicBoolean focusTaskRunning = new AtomicBoolean(false);
    private Runnable focusUpdateTask;

    // ====================== 管理器 ======================
    private BootStartManager bootStartManager;
    private AutoUpdateManager autoUpdateManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private static final int WEB_SERVER_PORT = 10481;
    private String currentWebUrl;
    private UpdateManager updateManager;

    // ====================== 复用弹窗EditText ======================
    private EditText globalUrlEdit;

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

    // ====================== 日志全局限制长度，防止内存爆炸 ======================
    public static volatile StringBuilder PLAY_LOG = new StringBuilder();
    public static volatile StringBuilder OPERATION_LOG = new StringBuilder();
    private static final int MAX_LOG_LENGTH = 15000;

    public static void log(String msg) {
        LogManager.log(msg);
        synchronized (PLAY_LOG) {
            PLAY_LOG.append(msg).append("\n");
            if (PLAY_LOG.length() > MAX_LOG_LENGTH) {
                PLAY_LOG.delete(0, PLAY_LOG.length() - MAX_LOG_LENGTH);
            }
        }
    }

    public static void logOperation(String msg) {
        LogManager.logOperation(msg);
        synchronized (OPERATION_LOG) {
            OPERATION_LOG.append(msg).append("\n");
            if (OPERATION_LOG.length() > MAX_LOG_LENGTH) {
                OPERATION_LOG.delete(0, OPERATION_LOG.length() - MAX_LOG_LENGTH);
            }
        }
    }

    // ====================== onCreate 精简无阻塞 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initWindowConfig();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);

        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());

        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        settingCache = new AppSettingCache(sp);
        initRedirectDefaultConfig();

        bindViews();
        initSettingsItemList();
        initRemoteManager();

        // 基础UI状态同步（仅内存缓存读取，无磁盘IO）
        syncSwitchState();
        updateDecoderModeText(settingCache.decoderMode);
        updateRendererModeText(settingCache.rendererMode);
        updateRedirectSettingText();

        bindItemClickListeners();

        // 非阻塞延迟初始化重型管理器、Web服务（主线程空闲再执行）
        mainHandler.postDelayed(this::initHeavyManagers, 120);
        mainHandler.postDelayed(this::startWebServerAsync, 300);

        logOperation("【设置】打开设置页面");
    }

    // 合并所有窗口/全屏代码，去除重复setAttributes
    private void initWindowConfig() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        // 沉浸式全屏
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);
    }

    // 仅findViewById，无业务逻辑
    private void bindViews() {
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

        // 全局复用输入框
        globalUrlEdit = new EditText(this);
        globalUrlEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(800)});
    }

    // 初始化Item列表 + 预缓存内部TextView，彻底删除递归findFirstTextView
    private void initSettingsItemList() {
        settingsItemList.clear();
        itemTextCache.clear();
        int[] itemIds = {
                R.id.item_boot, R.id.item_epg, R.id.item_auto_update, R.id.item_reverse,
                R.id.item_num_channel, R.id.item_pip, R.id.item_decoder, R.id.item_renderer,
                R.id.item_redirect, R.id.tv_screen_ratio, R.id.tv_custom_source,
                R.id.tv_custom_epg, R.id.tv_multi_source, R.id.tv_multi_epg,
                R.id.tv_qr_code, R.id.log_viewer, R.id.log_operation, R.id.item_check_update
        };
        for (int id : itemIds) {
            View item = findViewById(id);
            if (item == null) continue;
            settingsItemList.add(item);
            TextView cacheTv = extractItemTextView(item);
            itemTextCache.add(cacheTv);

            item.setFocusableInTouchMode(true);
            final int pos = settingsItemList.size() - 1;
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && remoteManager != null) {
                    remoteManager.setSettingsFocusPosition(pos);
                    throttleUpdateFocus();
                }
            });
        }
    }

    // 一次性提取Item内TextView，仅执行一次，不再递归遍历
    private TextView extractItemTextView(View item) {
        if (item instanceof TextView) return (TextView) item;
        if (item instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) item;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof TextView) return (TextView) child;
            }
        }
        return null;
    }

    // 焦点更新防抖节流，避免高频循环重绘
    private void throttleUpdateFocus() {
        if (focusTaskRunning.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(focusUpdateTask);
            focusUpdateTask = () -> {
                updateSettingsFocus();
                focusTaskRunning.set(false);
            };
            mainHandler.postDelayed(focusUpdateTask, FOCUS_DEBOUNCE_MS);
        }
    }

    // 重型管理器异步初始化，不阻塞UI
    private void initHeavyManagers() {
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        updateManager = new UpdateManager(this);
        bootStartManager.updateBootStatusText(tv_boot_status);
        if (settingCache.autoUpdateSource) {
            autoUpdateManager.setAutoUpdateAlarm();
        }
    }

    // Web服务后台子线程启动
    private void startWebServerAsync() {
        workExecutor.submit(() -> {
            webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
            webServerManager.start();
            currentWebUrl = webServerManager.getAccessUrl();
        });
    }

    // 开关状态仅读取内存缓存
    private void syncSwitchState() {
        sw_boot.setChecked(settingCache.bootAutoStart);
        sw_epg.setChecked(settingCache.epgEnable);
        sw_auto_update.setChecked(settingCache.autoUpdateSource);
        sw_reverse.setChecked(settingCache.channelReverse);
        sw_num_channel.setChecked(settingCache.numberChannelEnable);
        sw_pip.setChecked(settingCache.pipEnable);
    }

    // 绑定所有Item点击
    private void bindItemClickListeners() {
        findViewById(R.id.item_boot).setOnClickListener(v -> {
            boolean val = !sw_boot.isChecked();
            settingCache.bootAutoStart = val;
            sw_boot.setChecked(val);
            bootStartManager.toggleBoot(val, tv_boot_status);
        });
        findViewById(R.id.item_boot).setOnLongClickListener(v -> {
            bootStartManager.showBootStatusDialog();
            return true;
        });

        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean val = !sw_epg.isChecked();
            settingCache.epgEnable = val;
            sw_epg.setChecked(val);
            saveSingleBoolean("epg_enable", val);
            logOperation("【设置】节目单" + (val ? "已开启" : "已关闭"));
            Toast.makeText(this, "节目单" + (val ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.item_auto_update).setOnClickListener(v -> {
            boolean val = !sw_auto_update.isChecked();
            settingCache.autoUpdateSource = val;
            sw_auto_update.setChecked(val);
            saveSingleBoolean("auto_update_source", val);
            if (val) autoUpdateManager.setAutoUpdateAlarm();
            else autoUpdateManager.cancelAutoUpdateAlarm();
            logOperation("【设置】自动更新源" + (val ? "已开启" : "已关闭"));
            Toast.makeText(this, "自动更新源" + (val ? "已开启（每天凌晨4点）" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean val = !sw_reverse.isChecked();
            settingCache.channelReverse = val;
            sw_reverse.setChecked(val);
            saveSingleBoolean("channel_reverse", val);
            logOperation("【设置】换台反转" + (val ? "已开启" : "已关闭"));
            Toast.makeText(this, "换台反转" + (val ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean val = !sw_num_channel.isChecked();
            settingCache.numberChannelEnable = val;
            sw_num_channel.setChecked(val);
            saveSingleBoolean("number_channel_enable", val);
            logOperation("【设置】数字选台" + (val ? "已开启" : "已关闭"));
            Toast.makeText(this, "数字选台" + (val ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean val = !sw_pip.isChecked();
            settingCache.pipEnable = val;
            sw_pip.setChecked(val);
            saveSingleBoolean("pip_enable", val);
            logOperation("【设置】画中画（后台小窗播放）" + (val ? "已开启" : "已关闭"));
            Toast.makeText(this, val ? "画中画已开启，按Home键自动小窗播放" : "画中画已关闭", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.item_decoder).setOnClickListener(v -> {
            showDecoderModeDialog();
            logOperation("【设置】打开解码器选择");
        });
        findViewById(R.id.item_renderer).setOnClickListener(v -> {
            showRendererModeDialog();
            logOperation("【设置】打开渲染方式选择");
        });
        findViewById(R.id.item_redirect).setOnClickListener(v -> {
            showRedirectConfigDialog();
            logOperation("【设置】打开HTTP重定向配置");
        });
        findViewById(R.id.item_check_update).setOnClickListener(v -> {
            updateManager.checkUpdate();
            logOperation("【设置】点击检查更新");
        });
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialogAsync());
        findViewById(R.id.log_operation).setOnClickListener(v -> showOperationLogDialogAsync());

        // 普通文本项点击
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
        tv_qr_code.setOnClickListener(v -> workExecutor.submit(() -> {
            if (currentWebUrl != null) {
                mainHandler.post(() -> qrCodeManager.showQRCodeDialog(currentWebUrl));
            }
        }));
    }

    // 内存缓存SP写入工具，减少edit().apply()频繁IO
    private void saveSingleBoolean(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    // 初始化重定向默认配置（仅首次）
    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            sp.edit()
                    .putInt(KEY_REDIRECT_MAX_COUNT, 5)
                    .putBoolean(KEY_REDIRECT_CROSS_DOMAIN, true)
                    .putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true)
                    .putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true)
                    .putBoolean(KEY_REDIRECT_IGNORE_SSL, false)
                    .putBoolean(KEY_REDIRECT_SEND_COOKIE, true)
                    .putString(KEY_USER_AGENT_MODE, "exo")
                    .apply();
            logOperation("【设置】初始化重定向默认配置完成");
            settingCache.refreshRedirectCache();
        }
    }

    // 更新重定向摘要文本
    private void updateRedirectSettingText() {
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(settingCache.redirectMaxCount).append(" | ");
        sb.append("跨域：").append(settingCache.redirectCrossDomain ? "开" : "关").append(" | ");
        sb.append("跨协议：").append(settingCache.redirectCrossProtocol ? "开" : "关").append("\n");
        sb.append("携带请求头：").append(settingCache.redirectFollowHeader ? "开" : "关").append(" | ");
        sb.append("忽略SSL：").append(settingCache.redirectIgnoreSsl ? "开" : "关").append(" | ");
        sb.append("授权令牌：").append(settingCache.redirectSendCookie ? "开" : "关").append(" | ");
        sb.append("UA：").append("exo".equals(settingCache.userAgentMode) ? "ExoPlayer" : "VLC");
        tv_redirect_setting.setText(sb);
    }

    // 遥控器初始化
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
            @Override public boolean onPanelBack() { return false; }
            @Override public void onPanelMenu() {}
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override
            public void onSettingsMoveUp() { throttleUpdateFocus(); }
            @Override
            public void onSettingsMoveDown() { throttleUpdateFocus(); }
            @Override
            public void onSettingsConfirm() {
                int pos = remoteManager.getSettingsFocusPosition();
                handleSettingsItemClick(pos);
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
            @Override public void onSettingsFocusChanged(int position) { throttleUpdateFocus(); }
            @Override public boolean onPipBack() { return false; }
            @Override public void onRequestPlayFocus() {}
            @Override public void onChannelNumberSelected(int channelIndex) {}
            @Override public void onShowChannelNumber(String number) {}
            @Override public void onHideChannelNumber() {}
        });
    }

    // 按键分发
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====================== 🟢 修复焦点核心方法 ======================
    private void updateSettingsFocus() {
        int selectedPos = remoteManager.getSettingsFocusPosition();
        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            TextView textView = itemTextCache.get(i);
            if (textView == null) continue;

            if (i == selectedPos) {
                setItemStyle(textView, "#40A9FF", Typeface.BOLD, 0x3340A9FF, item);
                
                // 🟢 极重要：通过 post 让 UI 先完成布局，瞬间滚动到位，最后才请求焦点，彻底解决被系统拒绝的 bug
                item.post(() -> {
                    scrollToViewNoAnim(item);
                    item.requestFocus();
                });
            } else {
                setItemStyle(textView, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT, item);
            }
        }
    }

    // 无动画滚动，消除动画队列堆积
    private void scrollToViewNoAnim(View view) {
        if (scrollView == null || view == null) return;
        
        int top = view.getTop();
        int bottom = view.getBottom();
        int scrollH = scrollView.getHeight();
        int currScroll = scrollView.getScrollY();

        if (top < currScroll) {
            scrollView.scrollTo(0, Math.max(0, top - 50)); // 防止越界
        } else if (bottom > currScroll + scrollH) {
            scrollView.scrollTo(0, bottom - scrollH + 50);
        }
    }

    // 直接传入缓存TextView，删除递归查找
    private void setItemStyle(TextView tv, String textColor, int typeStyle, int bgColor, View item) {
        item.setBackgroundColor(bgColor);
        tv.setTextColor(Color.parseColor(textColor));
        tv.setTypeface(null, typeStyle);
    }

    private void handleSettingsItemClick(int position) {
        if (position < 0 || position >= settingsItemList.size()) return;
        View item = settingsItemList.get(position);
        if (item != null) item.performClick();
    }

    // 屏幕比例弹窗
    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        new AlertDialog.Builder(this)
                .setTitle("屏幕比例")
                .setItems(ratios, (d, w) -> {
                    sp.edit().putString("screen_ratio", ratios[w]).apply();
                    logOperation("【设置】屏幕比例设为：" + ratios[w]);
                    Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
                }).show();
    }

    // 解码器弹窗
    private void showDecoderModeDialog() {
        final String[] modes = {"自动（推荐）", "硬解", "软解（兼容性好）"};
        final String[] modeValues = {"auto", "hard", "soft"};
        int checked = "hard".equals(settingCache.decoderMode) ? 1 : "soft".equals(settingCache.decoderMode) ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("解码器选择")
                .setSingleChoiceItems(modes, checked, (d, which) -> {
                    String val = modeValues[which];
                    settingCache.decoderMode = val;
                    sp.edit().putString("decoder_mode", val).apply();
                    updateDecoderModeText(val);
                    logOperation("【设置】解码器选择：" + modes[which]);
                    sendBroadcast(new Intent("com.tv.live.DECODER_MODE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateDecoderModeText(String mode) {
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            default: tv_decoder_mode.setText("自动");
        }
    }

    // 渲染方式弹窗
    private void showRendererModeDialog() {
        final String[] modes = {"SurfaceView（默认）", "TextureView（兼容）"};
        final String[] modeValues = {"surface", "texture"};
        int checked = "texture".equals(settingCache.rendererMode) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("渲染方式选择")
                .setSingleChoiceItems(modes, checked, (d, which) -> {
                    String val = modeValues[which];
                    settingCache.rendererMode = val;
                    sp.edit().putString("renderer_type", val).apply();
                    updateRendererModeText(val);
                    logOperation("【设置】渲染方式：" + modes[which]);
                    sendBroadcast(new Intent("com.tv.live.RENDERER_TYPE_CHANGED"));
                    d.dismiss();
                    Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void updateRendererModeText(String mode) {
        tv_renderer_type.setText("texture".equals(mode) ? "TextureView" : "SurfaceView");
    }

    // 重定向配置弹窗
    private void showRedirectConfigDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_redirect_config, null);
        EditText etMax = dialogView.findViewById(R.id.et_redirect_max);
        SwitchCompat swCrossDomain = dialogView.findViewById(R.id.sw_cross_domain);
        SwitchCompat swCrossProto = dialogView.findViewById(R.id.sw_cross_proto);
        SwitchCompat swFollowHeader = dialogView.findViewById(R.id.sw_follow_header);
        SwitchCompat swIgnoreSsl = dialogView.findViewById(R.id.sw_ignore_ssl);
        SwitchCompat swSendCookie = dialogView.findViewById(R.id.sw_send_cookie);
        LinearLayout llUserAgent = dialogView.findViewById(R.id.ll_user_agent);
        TextView tvUserAgentStatus = dialogView.findViewById(R.id.tv_user_agent_status);

        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(settingCache.redirectMaxCount));
        swCrossDomain.setChecked(settingCache.redirectCrossDomain);
        swCrossProto.setChecked(settingCache.redirectCrossProtocol);
        swFollowHeader.setChecked(settingCache.redirectFollowHeader);
        swIgnoreSsl.setChecked(settingCache.redirectIgnoreSsl);
        swSendCookie.setChecked(settingCache.redirectSendCookie);
        final String[] uaVal = {settingCache.userAgentMode};
        tvUserAgentStatus.setText("exo".equals(uaVal[0]) ? "ExoPlayer默认" : "VLC播放器");

        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "VLC播放器"};
            final String[] uaValues = {"exo", "vlc"};
            int c = "vlc".equals(uaVal[0]) ? 1 : 0;
            new AlertDialog.Builder(this)
                    .setTitle("UA切换")
                    .setSingleChoiceItems(uaOptions, c, (d, which) -> {
                        uaVal[0] = uaValues[which];
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
                            newMax = Math.max(1, Math.min(20, newMax));
                        } catch (Exception ignored) {}
                    }
                    settingCache.redirectMaxCount = newMax;
                    settingCache.redirectCrossDomain = swCrossDomain.isChecked();
                    settingCache.redirectCrossProtocol = swCrossProto.isChecked();
                    settingCache.redirectFollowHeader = swFollowHeader.isChecked();
                    settingCache.redirectIgnoreSsl = swIgnoreSsl.isChecked();
                    settingCache.redirectSendCookie = swSendCookie.isChecked();
                    settingCache.userAgentMode = uaVal[0];

                    sp.edit()
                            .putInt(KEY_REDIRECT_MAX_COUNT, newMax)
                            .putBoolean(KEY_REDIRECT_CROSS_DOMAIN, settingCache.redirectCrossDomain)
                            .putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, settingCache.redirectCrossProtocol)
                            .putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, settingCache.redirectFollowHeader)
                            .putBoolean(KEY_REDIRECT_IGNORE_SSL, settingCache.redirectIgnoreSsl)
                            .putBoolean(KEY_REDIRECT_SEND_COOKIE, settingCache.redirectSendCookie)
                            .putString(KEY_USER_AGENT_MODE, settingCache.userAgentMode)
                            .apply();
                    updateRedirectSettingText();
                    logOperation("【设置】重定向配置已保存，最大跳转：" + newMax);
                    Toast.makeText(this, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 复用全局EditText输入弹窗
    private void showInputDialog(String title, String hint, String key) {
        globalUrlEdit.setHint(hint);
        globalUrlEdit.setText(sp.getString(key, ""));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(globalUrlEdit)
                .setPositiveButton("确定", (d, w) -> {
                    String url = globalUrlEdit.getText().toString().trim();
                    if (!url.isEmpty()) {
                        sp.edit().putString(key, url).apply();
                        String histKey = key.contains("live") ? "live_history" : "epg_history";
                        SourceManager sourceManager = new SourceManager(this, histKey);
                        String shortName = url.length() > 10 ? url.substring(0, 10) + "..." : url;
                        sourceManager.addSource(shortName, url);
                        sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                        logOperation("【设置】" + title + "已更新：" + url);
                        Toast.makeText(this, "已保存，正在刷新…", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 子线程预处理播放日志，主线程仅渲染
    private void showLogDialogAsync() {
        workExecutor.submit(() -> {
            List<String> lagLines = new ArrayList<>();
            String fullText;
            synchronized (PLAY_LOG) {
                String raw = PLAY_LOG.toString();
                String[] lines = raw.split("\n");
                StringBuilder reverseSb = new StringBuilder();
                String[] lagKeywords = {
                        "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                        "buffer underflow", "frame drop", "404", "buffering", "stall",
                        "delay", "timeout", "decoder error", "Forbidden", "访问拒绝",
                        "跳转失败", "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
                };
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    boolean hit = false;
                    for (String kw : lagKeywords) {
                        if (line.contains(kw)) {
                            hit = true;
                            break;
                        }
                    }
                    if (hit && !lagLines.contains(line)) lagLines.add(line);
                    reverseSb.append(line).append("\n");
                }
                StringBuilder full = new StringBuilder();
                full.append("========== 卡顿原因分析汇总 ==========\n");
                if (lagLines.isEmpty()) full.append("未检测到卡顿相关日志\n");
                else for (String s : lagLines) full.append(s).append("\n");
                full.append("\n========== 完整播放日志 ==========\n");
                full.append(reverseSb);
                fullText = full.toString();
            }
            final String logContent = fullText;
            mainHandler.post(() -> renderPlayLogDialog(logContent));
        });
    }

    // 主线程仅渲染已处理好的日志文本
    private void renderPlayLogDialog(String logContent) {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        SpannableString spannable = new SpannableString(logContent);
        String[] lagKeywords = {
                "卡顿", "超时", "解码失败", "帧率下降", "网络延迟", "丢包",
                "buffer underflow", "frame drop", "404", "buffering", "stall",
                "delay", "timeout", "decoder error", "Forbidden", "访问拒绝",
                "跳转失败", "连接失败", "解析失败", "服务器拒绝", "无法拉流", "ssl错误"
        };
        for (String kw : lagKeywords) {
            int idx = 0;
            while ((idx = logContent.indexOf(kw, idx)) != -1) {
                spannable.setSpan(new ForegroundColorSpan(Color.RED), idx, idx + kw.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                idx += kw.length();
            }
        }
        tv.setText(spannable);
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("📄 解析 & 播放日志（卡顿分析）")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空日志", (d, w) -> {
                    LogManager.clearPlayLog();
                    synchronized (PLAY_LOG) { PLAY_LOG.setLength(0); }
                    logOperation("【设置】解析日志已清空");
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // 操作日志异步加载
    private void showOperationLogDialogAsync() {
        workExecutor.submit(() -> {
            String text;
            synchronized (OPERATION_LOG) {
                if (OPERATION_LOG.length() == 0) {
                    text = "暂无操作日志。\n\n操作日志会记录您的切台、切换分组、打开设置等操作，\n以及网页后台的启动、请求、响应等详细信息。";
                } else {
                    String raw = OPERATION_LOG.toString();
                    String[] lines = raw.split("\n");
                    StringBuilder sb = new StringBuilder();
                    for (int i = lines.length - 1; i >= 0; i--) {
                        String l = lines[i].trim();
                        if (!l.isEmpty()) sb.append(l).append("\n");
                    }
                    text = sb.toString();
                }
            }
            final String logText = text;
            mainHandler.post(() -> renderOperationLogDialog(logText));
        });
    }

    private void renderOperationLogDialog(String logText) {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(logText);
        tv.setTextSize(12);
        tv.setPadding(40, 40, 40, 40);
        tv.setTextColor(Color.BLACK);
        scrollView.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("📌 操作日志")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空日志", (d, w) -> {
                    LogManager.clearOperationLog();
                    synchronized (OPERATION_LOG) { OPERATION_LOG.setLength(0); }
                    logOperation("【设置】操作日志已清空");
                    Toast.makeText(this, "操作日志已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            initWindowConfig();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logOperation("【设置】关闭设置页面");
        mainHandler.removeCallbacksAndMessages(null);
        workExecutor.shutdownNow();
        if (webServerManager != null) {
            workExecutor.submit(() -> webServerManager.stop());
        }
        if (updateManager != null) updateManager.release();
        remoteManager = null;
        settingsItemList.clear();
        itemTextCache.clear();
    }

    // ====================== SP内存缓存内部类，消除频繁磁盘读取 ======================
    private class AppSettingCache {
        private final SharedPreferences sp;
        // 开关
        boolean bootAutoStart;
        boolean epgEnable;
        boolean autoUpdateSource;
        boolean channelReverse;
        boolean numberChannelEnable;
        boolean pipEnable;
        // 播放器
        String decoderMode;
        String rendererMode;
        // 重定向网络
        int redirectMaxCount;
        boolean redirectCrossDomain;
        boolean redirectCrossProtocol;
        boolean redirectFollowHeader;
        boolean redirectIgnoreSsl;
        boolean redirectSendCookie;
        String userAgentMode;

        AppSettingCache(SharedPreferences sp) {
            this.sp = sp;
            refreshAllCache();
        }

        void refreshAllCache() {
            bootAutoStart = sp.getBoolean("boot_auto_start", false);
            epgEnable = sp.getBoolean("epg_enable", true);
            autoUpdateSource = sp.getBoolean("auto_update_source", true);
            channelReverse = sp.getBoolean("channel_reverse", false);
            numberChannelEnable = sp.getBoolean("number_channel_enable", true);
            pipEnable = sp.getBoolean("pip_enable", false);
            decoderMode = sp.getString("decoder_mode", "auto");
            rendererMode = sp.getString("renderer_type", "surface");
            refreshRedirectCache();
        }

        void refreshRedirectCache() {
            redirectMaxCount = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
            redirectCrossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
            redirectCrossProtocol = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
            redirectFollowHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
            redirectIgnoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
            redirectSendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            userAgentMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        }
    }
}
