package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.tv.live.manager.TvRemoteManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity
 * 已修复遥控器焦点移动、触摸点击高亮跟随以及点击日志卡顿的问题
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_epg, sw_auto_update, sw_reverse, sw_num_channel, sw_pip;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;
    
    private SharedPreferences sp;
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
    private static final String KEY_REDIRECT_MAX_COUNT = "redirect_max_count";
    private static final String KEY_REDIRECT_CROSS_DOMAIN = "redirect_cross_domain";
    private static final String KEY_REDIRECT_CROSS_PROTOCOL = "redirect_cross_protocol";
    private static final String KEY_REDIRECT_FOLLOW_HEADERS = "redirect_follow_headers";
    private static final String KEY_REDIRECT_IGNORE_SSL = "redirect_ignore_ssl";
    private static final String KEY_REDIRECT_SEND_COOKIE = "redirect_send_cookie";
    private static final String KEY_USER_AGENT_MODE = "user_agent_mode";
    private static final String KEY_CHANNEL_LINE_INDEX = "channel_line_index";

    public static volatile StringBuffer PLAY_LOG = new StringBuffer();
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

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable focusUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🟢 确保 super.onCreate 始终是第一行！
        super.onCreate(savedInstanceState);
        
        try { applyFullScreen(); } catch (Exception e) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);
        
        bootStartManager = new BootStartManager(this, sp);
        autoUpdateManager = new AutoUpdateManager(this);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);
        
        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        initSettingsItemList();
        initRemoteManager();
        findViewById(R.id.log_viewer).setOnClickListener(v -> showLogDialog());

        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        tv_channel_line.setText(getLineName(currentLineIndex));
        findViewById(R.id.item_channel_line).setOnClickListener(v -> showChannelLineDialog());

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
        sw_epg.setChecked(sp.getBoolean("epg_enable", true));
        findViewById(R.id.item_epg).setOnClickListener(v -> {
            boolean isChecked = !sw_epg.isChecked();
            sw_epg.setChecked(isChecked);
            sp.edit().putBoolean("epg_enable", isChecked).apply();
            Toast.makeText(this, "节目单" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
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
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
        sw_num_channel.setChecked(sp.getBoolean("number_channel_enable", true));
        findViewById(R.id.item_num_channel).setOnClickListener(v -> {
            boolean isChecked = !sw_num_channel.isChecked();
            sw_num_channel.setChecked(isChecked);
            sp.edit().putBoolean("number_channel_enable", isChecked).apply();
            Toast.makeText(this, "数字选台" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });
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
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> showDecoderModeDialog());
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> showRendererModeDialog());
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> showRedirectConfigDialog());
        findViewById(R.id.item_check_update).setOnClickListener(v -> updateManager.checkUpdate());
        initListeners();
        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(this, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        List<String> lineList = new ArrayList<>();
        lineList.add("主源");
        for (int i = 1; i <= currentChannel.getBackupUrls().size(); i++) {
            lineList.add("源" + i);
        }
        String[] lineArray = lineList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("频道线路选择")
                .setSingleChoiceItems(lineArray, currentLineIndex, (dialog, which) -> {
                    sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
                    tv_channel_line.setText(lineArray[which]);
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
        settingsItemList.add(itemLiveSubscribe);
        settingsItemList.add(itemEpgSubscribe);
        settingsItemList.add(findViewById(R.id.item_channel_line));
        settingsItemList.add(findViewById(R.id.log_viewer));
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
            @Override public void onSettingsMoveUp() { updateSettingsFocus(); }
            @Override public void onSettingsMoveDown() { updateSettingsFocus(); }
            @Override public void onSettingsConfirm() { int position = remoteManager.getSettingsFocusPosition(); handleSettingsItemClick(position); }
            @Override public boolean onSettingsBack() { finish(); return true; }
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
        itemLiveSubscribe.setOnClickListener(v -> showSubscriptionDialog("live_history", "直播源订阅"));
        itemEpgSubscribe.setOnClickListener(v -> showSubscriptionDialog("epg_history", "节目单订阅"));
    }

    // ================= 🛠️ 重点修改：弹窗 UI 统一（去白边） =================
    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(this, spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_subscription, null);
        ListView lvSourceList = dialogView.findViewById(R.id.lv_source_list);
        ImageView ivQrCode = dialogView.findViewById(R.id.iv_qr_code);
        TextView tvIpAddress = dialogView.findViewById(R.id.tv_ip_address);
        EditText etName = dialogView.findViewById(R.id.et_name);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        Button btnClear = dialogView.findViewById(R.id.btn_clear);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnSavePermission = dialogView.findViewById(R.id.btn_save_permission);

        // 🆕 获取自定义布局中的标题和关闭按钮
        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        // 动态设置标题文字
        if (tvDialogTitle != null) {
            tvDialogTitle.setText(title);
        }

        tvIpAddress.setText(currentWebUrl);

        try {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
            ivQrCode.setBackgroundColor(Color.LTGRAY);
        } catch (Exception e) {
            ivQrCode.setBackgroundColor(Color.LTGRAY);
        }

        ivQrCode.setOnClickListener(v -> {
            qrCodeManager.showQRCodeDialog(currentWebUrl);
        });

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(this, sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                sourceManager.setDefault(position);
                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
                Toast.makeText(SettingsActivity.this, "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDelete(int position) {
                // 🛡️ 已修复：新增防御性检查，防止 index=-1 导致崩溃
                if (position < 0 || position >= sources.size()) {
                    return;
                }
                SourceManager.SourceItem item = sources.get(position);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            sourceManager.removeSource(sourceManager.indexOfUrl(item.url));
                            sources.clear();
                            sources.addAll(sourceManager.getAllSources());
                            adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                            adapter.notifyDataSetChanged();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        lvSourceList.setAdapter(adapter);

        btnConfirm.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(this, "已添加，正在刷新...", Toast.LENGTH_SHORT).show();
                sendBroadcast(new Intent("com.tv.live.REFRESH_LIVE_AND_EPG"));
            } else {
                Toast.makeText(this, "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        btnSavePermission.setOnClickListener(v -> Toast.makeText(this, "存储权限功能暂未实现", Toast.LENGTH_SHORT).show());

        // 🆕 去掉系统自带的白色 Title 和底部按钮，改为纯自定义视图
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        
        // 🎯 关键：将 Dialog 背景设为透明，彻底干掉白色边框
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        
        dialog.show();

        // 绑定自定义的关闭按钮
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
    }
    // ================= 🛠️ 重点修改结束 =================

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
        if (selectedPosition < 0 || selectedPosition >= settingsItemList.size()) return;

        View target = settingsItemList.get(selectedPosition);
        if (target == null) return;

        for (int i = 0; i < settingsItemList.size(); i++) {
            View item = settingsItemList.get(i);
            if (item == null) continue;
            if (i == selectedPosition) {
                setItemStyle(item, "#40A9FF", Typeface.BOLD, 0x3340A9FF);
            } else {
                setItemStyle(item, "#FFFFFF", Typeface.NORMAL, Color.TRANSPARENT);
            }
        }

        if (focusUpdateRunnable != null) {
            mainHandler.removeCallbacks(focusUpdateRunnable);
        }
        focusUpdateRunnable = () -> {
            scrollToView(target);
            target.requestFocus();
        };
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
            case "texture": tv_renderer_type.setText("TextureView"); break;
            case "surface": default: tv_renderer_type.setText("SurfaceView"); break;
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
                        }catch (Exception ignored){ newMax =5; }
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
