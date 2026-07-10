package com.tv.live;

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
import android.widget.ArrayAdapter;
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

import com.tv.live.TVPlayerManager;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private SwitchCompat sw_boot, sw_reverse, sw_pip;
    private TextView tv_screen_ratio, tv_decoder_mode, tv_renderer_type, tv_redirect_setting, tv_boot_status;
    private TextView tv_channel_line;
    private TextView tv_resolution_status;
    private View itemResolution;
    private View itemLog;
    private TextView tv_log_status;
    private View itemVersionInfo;
    private TextView tv_version_short;
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;

    private SharedPreferences sp;
    private List<View> settingsItemList = new ArrayList<>();
    private ScrollView scrollView;

    private BootStartManager bootStartManager;
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

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullScreen();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.dimAmount = 0f;
        getWindow().setAttributes(layoutParams);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(R.layout.activity_settings);
        View viewOutside = findViewById(R.id.view_outside);
        viewOutside.setOnClickListener(v -> finish());
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        initRedirectDefaultConfig();

        sp.edit().putBoolean("epg_enable", true).apply();
        sp.edit().putBoolean("number_channel_enable", true).apply();

        sw_boot = findViewById(R.id.sw_boot);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_pip = findViewById(R.id.sw_pip);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        scrollView = findViewById(R.id.settings_content);

        itemResolution = findViewById(R.id.item_resolution);
        tv_resolution_status = findViewById(R.id.tv_resolution_status);

        itemLog = findViewById(R.id.item_log);
        tv_log_status = findViewById(R.id.tv_log_status);

        itemVersionInfo = findViewById(R.id.item_version_info);
        tv_version_short = findViewById(R.id.tv_version_short);

        bootStartManager = new BootStartManager(this, sp);
        sourceDialogManager = new SourceDialogManager(this, sp);
        qrCodeManager = new QRCodeManager(this);
        webServerManager = new WebServerManager(this, WEB_SERVER_PORT);
        updateManager = new UpdateManager(this);

        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);

        // 初始化各条目点击事件（所有方向键由系统原生焦点处理，不再需要手动管理）
        initListeners();

        webServerManager.start();
        currentWebUrl = webServerManager.getAccessUrl();

        SourceManager liveManager = new SourceManager(this, "live_history");
        if (liveManager.size() == 0) {
            liveManager.addSource("默认直播源", UrlConfig.LIVE_URL);
        }
        SourceManager epgManager = new SourceManager(this, "epg_history");
        if (epgManager.size() == 0) {
            epgManager.addSource("默认节目单", UrlConfig.EPG_URL);
        }
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
        } catch (Exception e) { }
    }

    private void initRedirectDefaultConfig() {
        if (!sp.contains(KEY_REDIRECT_MAX_COUNT)) {
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(KEY_REDIRECT_MAX_COUNT, 5);
            editor.putBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
            editor.putBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
            editor.putBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
            editor.putBoolean(KEY_REDIRECT_IGNORE_SSL, false);
            editor.putBoolean(KEY_REDIRECT_SEND_COOKIE, true);
            editor.putString(KEY_USER_AGENT_MODE, "exo");
            editor.apply();
        }
    }

    private void initListeners() {
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

        // 反转换台
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        findViewById(R.id.item_reverse).setOnClickListener(v -> {
            boolean isChecked = !sw_reverse.isChecked();
            sw_reverse.setChecked(isChecked);
            sp.edit().putBoolean("channel_reverse", isChecked).apply();
            Toast.makeText(this, "换台反转" + (isChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
        });

        // 画中画
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        findViewById(R.id.item_pip).setOnClickListener(v -> {
            boolean isChecked = !sw_pip.isChecked();
            sw_pip.setChecked(isChecked);
            sp.edit().putBoolean("pip_enable", isChecked).apply();
            Toast.makeText(this, "画中画已" + (isChecked ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });

        // 解码器
        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        findViewById(R.id.item_decoder).setOnClickListener(v -> showDecoderModeDialog());

        // 渲染方式
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        findViewById(R.id.item_renderer).setOnClickListener(v -> showRendererModeDialog());

        // 重定向设置
        updateRedirectSettingText();
        findViewById(R.id.item_redirect).setOnClickListener(v -> showRedirectConfigDialog());

        // 检查更新
        findViewById(R.id.item_check_update).setOnClickListener(v -> updateManager.checkUpdate());

        // 分辨率
        itemResolution.setOnClickListener(v -> showResolutionDialog());

        // 版本信息
        itemVersionInfo.setOnClickListener(v -> showVersionInfoDialog());

        // 日志开关
        itemLog.setOnClickListener(v -> {
            boolean logEnabled = sp.getBoolean("log_enable", false);
            boolean newState = !logEnabled;
            sp.edit().putBoolean("log_enable", newState).apply();
            tv_log_status.setText(newState ? "开启" : "关闭");
            Toast.makeText(SettingsActivity.this, "日志已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
            MainActivity.toggleLogWindow(newState);
        });

        // 直播源订阅
        itemLiveSubscribe.setOnClickListener(v -> showSubscriptionDialog("live_history", "直播源订阅"));

        // EPG订阅
        itemEpgSubscribe.setOnClickListener(v -> showSubscriptionDialog("epg_history", "节目单订阅"));

        // 屏幕比例
        tv_screen_ratio.setOnClickListener(v -> showRatioDialog());

        // 频道线路
        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        tv_channel_line.setText(getLineName(currentLineIndex));
        findViewById(R.id.item_channel_line).setOnClickListener(v -> showChannelLineDialog());

        // 版本信息（长按）
        itemVersionInfo.setOnLongClickListener(v -> {
            showVersionInfoDialog();
            return true;
        });
    }

    // ---------- 各种对话框方法（保持原有逻辑） ----------
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

        showDarkSingleChoiceDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();
            tv_channel_line.setText(lineArray[which]);
            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
    }

    private void showDarkSingleChoiceDialog(String title, String[] items, int checkedItem, java.util.function.Consumer<Integer> onSelected) {
        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            onSelected.accept(position);
        });

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.addView(titleView);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void showRatioDialog() {
        final String[] ratios = {"全屏", "填充", "原始"};
        String currentMode = sp.getString("screen_ratio", "全屏");
        int checkedItem = 0;
        for (int i = 0; i < ratios.length; i++) {
            if (ratios[i].equals(currentMode)) {
                checkedItem = i;
                break;
            }
        }
        showDarkSingleChoiceDialog("屏幕比例", ratios, checkedItem, (which) -> {
            sp.edit().putString("screen_ratio", ratios[which]).apply();
            Toast.makeText(this, "已设置", Toast.LENGTH_SHORT).show();
        });
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
        showDarkSingleChoiceDialog("解码器选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("decoder_mode", selectedMode).apply();
            updateDecoderModeText(selectedMode);
            Intent intent = new Intent("com.tv.live.DECODER_MODE_CHANGED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDecoderModeText(String mode) {
        if (tv_decoder_mode == null) return;
        switch (mode) {
            case "hard": tv_decoder_mode.setText("硬解"); break;
            case "soft": tv_decoder_mode.setText("软解"); break;
            case "auto":
            default: tv_decoder_mode.setText("自动"); break;
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
        showDarkSingleChoiceDialog("渲染方式选择", modes, checkedItem, (which) -> {
            String selectedMode = modeValues[which];
            sp.edit().putString("renderer_type", selectedMode).apply();
            updateRendererModeText(selectedMode);
            Intent intent = new Intent("com.tv.live.RENDERER_TYPE_CHANGED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateRendererModeText(String mode) {
        if (tv_renderer_type == null) return;
        switch (mode) {
            case "texture": tv_renderer_type.setText("TextureView"); break;
            case "surface":
            default: tv_renderer_type.setText("SurfaceView"); break;
        }
    }

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        if (playerManager == null) return;
        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(this, "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = resolutions.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择清晰度")
                .setItems(items, (dialog, which) -> {
                    String selectedLabel = items[which];
                    int targetHeight = 0;
                    if (selectedLabel.contains("4K")) targetHeight = 2160;
                    else if (selectedLabel.contains("1080p")) targetHeight = 1080;
                    else if (selectedLabel.contains("720p")) targetHeight = 720;
                    else {
                        try {
                            targetHeight = Integer.parseInt(selectedLabel.replace("p", ""));
                        } catch (Exception ignored) {}
                    }
                    if (targetHeight > 0) {
                        playerManager.switchToResolution(targetHeight);
                        tv_resolution_status.setText(selectedLabel);
                        Toast.makeText(SettingsActivity.this, "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(this, spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
        );
        View dialogView = inflater.inflate(R.layout.dialog_subscription, null);

        ListView lvSourceList = dialogView.findViewById(R.id.lv_source_list);
        ImageView ivQrCode = dialogView.findViewById(R.id.iv_qr_code);
        TextView tvIpAddress = dialogView.findViewById(R.id.tv_ip_address);
        TextView tvDialogTitle = dialogView.findViewById(R.id.tv_dialog_title);
        LinearLayout llScanHeader = dialogView.findViewById(R.id.ll_scan_header);
        EditText etName = dialogView.findViewById(R.id.et_name);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        Button btnClear = dialogView.findViewById(R.id.btn_clear);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        boolean isLive = "live_history".equals(spKey);
        tvIpAddress.setText(currentWebUrl);

        if (isLive) {
            tvDialogTitle.setText(title);
            llScanHeader.setVisibility(View.VISIBLE);
            ivQrCode.setVisibility(View.VISIBLE);
            new Thread(() -> {
                Bitmap qrBitmap = qrCodeManager.createQR(currentWebUrl, 240);
                runOnUiThread(() -> {
                    if (qrBitmap != null) ivQrCode.setImageBitmap(qrBitmap);
                    else ivQrCode.setBackgroundColor(Color.LTGRAY);
                });
            }).start();
            ivQrCode.setOnClickListener(v -> Toast.makeText(SettingsActivity.this, "已生成二维码，请扫码", Toast.LENGTH_SHORT).show());
            etName.setHint("请输入名称(选填)");
            etUrl.setHint("请输入地址");
        } else {
            tvDialogTitle.setText(title);
            llScanHeader.setVisibility(View.GONE);
            ivQrCode.setVisibility(View.GONE);
            etName.setHint("请输入节目单名称(选填)");
            etUrl.setHint("请输入EPG节目单地址");
        }

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(this, sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                sourceManager.setDefault(position);
                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
                Toast.makeText(SettingsActivity.this, "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDelete(int position) {
                if (position < 0 || position >= sources.size()) return;
                SourceManager.SourceItem item = sources.get(position);
                AlertDialog deleteDialog = new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「" + item.name + "」吗？")
                        .setPositiveButton("删除", (d, w) -> {
                            int realIndex = sourceManager.indexOfUrl(item.url);
                            if (realIndex >= 0 && realIndex < sourceManager.size()) {
                                sourceManager.removeSource(realIndex);
                                sources.clear();
                                sources.addAll(sourceManager.getAllSources());
                                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                                adapter.notifyDataSetChanged();
                                Toast.makeText(SettingsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(SettingsActivity.this, "删除失败，源未找到", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .create();
                if (deleteDialog.getWindow() != null) {
                    deleteDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                deleteDialog.show();
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
                deleteDialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF55576A));
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
                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            } else {
                Toast.makeText(this, "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private void showRedirectConfigDialog() {
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
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
        Button btnCancel = dialogView.findViewById(R.id.btn_redirect_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_redirect_save);

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
            showDarkSingleChoiceDialog("UA切换", uaOptions, checkedItem, (which) -> {
                currentUaMode[0] = uaValues[which];
                tvUserAgentStatus.setText(uaOptions[which]);
            });
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String maxStr = etMax.getText().toString().trim();
            int newMax = 5;
            if (!TextUtils.isEmpty(maxStr)) {
                try {
                    newMax = Integer.parseInt(maxStr);
                    if (newMax < 1) newMax = 1;
                    if (newMax > 20) newMax = 20;
                } catch (Exception ignored) { newMax = 5; }
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
            dialog.dismiss();
        });
    }

    private void updateRedirectSettingText() {
        int max = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
        String uaLabel = "exo".equals(uaMode) ? "ExoPlayer" : "VLC";
        StringBuilder sb = new StringBuilder();
        sb.append("最大跳转：").append(max).append(" | ");
        sb.append("跨域：").append(crossDomain ? "开" : "关").append(" | ");
        sb.append("跨协议：").append(crossProto ? "开" : "关").append("\n");
        sb.append("携带请求头：").append(followHeader ? "开" : "关").append(" | ");
        sb.append("忽略SSL：").append(ignoreSsl ? "开" : "关").append(" | ");
        sb.append("授权令牌：").append(sendCookie ? "开" : "关").append(" | ");
        sb.append("UA：").append(uaLabel);
        tv_redirect_setting.setText(sb.toString());
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showVersionInfoDialog() {
        String versionName = BuildConfig.VERSION_NAME;
        int versionCode = BuildConfig.VERSION_CODE;
        String updateNotes = updateManager.getUpdateMessage();
        String userAgent = sp.getString("custom_user_agent", "");
        if (TextUtils.isEmpty(userAgent)) {
            String uaMode = sp.getString(KEY_USER_AGENT_MODE, "exo");
            if ("vlc".equals(uaMode)) {
                userAgent = "VLC/3.0.21 LibVLC/3.0.21";
            } else {
                userAgent = "ExoPlayer";
            }
        }
        String sdkVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String playerVersion = "androidx.media3 1.7.1";

        String message = "版本信息: v" + versionName + " (" + versionCode + ")\n\n" +
                "更新内容: \n" + updateNotes + "\n\n" +
                "UA: " + userAgent + "\n\n" +
                "SDK 版本: " + sdkVersion + "\n\n" +
                "播放器版本: " + playerVersion;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.setPadding(24, 24, 24, 24);

        TextView titleView = new TextView(this);
        titleView.setText("📱 应用详情");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        layout.addView(titleView);

        TextView msgView = new TextView(this);
        msgView.setText(message);
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(16);
        msgView.setPadding(0, 16, 0, 0);
        layout.addView(msgView);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFullScreen();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.dimAmount = 0f;
            getWindow().setAttributes(layoutParams);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServerManager != null) webServerManager.stop();
        if (updateManager != null) updateManager.release();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
