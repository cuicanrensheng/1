package com.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SettingsDialog extends Dialog {

    private final Context context;
    private SharedPreferences sp;
    private BootStartManager bootStartManager;
    private SourceDialogManager sourceDialogManager;
    private QRCodeManager qrCodeManager;
    private WebServerManager webServerManager;
    private UpdateManager updateManager;

    // 控件
    private SwitchCompat sw_boot, sw_reverse, sw_pip;
    private TextView tv_boot_status, tv_screen_ratio, tv_decoder_mode, tv_renderer_type,
            tv_redirect_setting, tv_channel_line, tv_resolution_status, tv_log_status, tv_version_short;
    private View itemResolution, itemLog, itemVersionInfo;
    private LinearLayout itemLiveSubscribe, itemEpgSubscribe;
    private ScrollView scrollView;
    private int selectedItemPosition = 0;

    // 常量
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

    public SettingsDialog(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.END | Gravity.TOP);
            window.setDimAmount(0.0f);
            window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_settings);

        sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        initRedirectDefaultConfig();

        bindViews();

        bootStartManager = new BootStartManager(context, sp);
        sourceDialogManager = new SourceDialogManager(context, sp);
        qrCodeManager = new QRCodeManager(context);
        webServerManager = new WebServerManager(context, 10481);
        updateManager = new UpdateManager(context);

        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));

        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        updateRedirectSettingText();

        tv_channel_line.setText(getLineName(getCurrentLineIndex()));

        initSettingsItemList();

        webServerManager.start();

        View viewOutside = findViewById(R.id.view_outside);
        if (viewOutside != null) {
            viewOutside.setOnClickListener(v -> dismiss());
        }

        setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dismiss();
                return true;
            }
            return false;
        });
    }

    private void bindViews() {
        sw_boot = findViewById(R.id.sw_boot);
        sw_reverse = findViewById(R.id.sw_reverse);
        sw_pip = findViewById(R.id.sw_pip);
        tv_boot_status = findViewById(R.id.tv_boot_status);
        tv_screen_ratio = findViewById(R.id.tv_screen_ratio);
        tv_decoder_mode = findViewById(R.id.tv_decoder_mode);
        tv_renderer_type = findViewById(R.id.tv_renderer_type);
        tv_redirect_setting = findViewById(R.id.tv_redirect_setting);
        tv_channel_line = findViewById(R.id.tv_channel_line);
        tv_resolution_status = findViewById(R.id.tv_resolution_status);
        tv_log_status = findViewById(R.id.tv_log_status);
        tv_version_short = findViewById(R.id.tv_version_short);
        itemResolution = findViewById(R.id.item_resolution);
        itemLog = findViewById(R.id.item_log);
        itemVersionInfo = findViewById(R.id.item_version_info);
        itemLiveSubscribe = findViewById(R.id.item_live_subscribe);
        itemEpgSubscribe = findViewById(R.id.item_epg_subscribe);
        scrollView = findViewById(R.id.settings_content);
    }

    private void initSettingsItemList() {
        View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_check_update),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_log)
        };

        for (View item : items) {
            item.setBackgroundResource(R.drawable.item_settings_bg);
            item.setFocusable(true);
            item.setClickable(true);
        }

        for (int i = 0; i < items.length; i++) {
            View item = items[i];
            int finalI = i;
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    if (selectedItemPosition != finalI) {
                        items[selectedItemPosition].setSelected(false);
                        items[finalI].setSelected(true);
                        selectedItemPosition = finalI;
                    }
                }
                v.setBackgroundResource(R.drawable.item_settings_bg);
            });
        }

        View.OnClickListener clickListener = v -> {
            int clickedIndex = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    clickedIndex = i;
                    break;
                }
            }
            if (clickedIndex == -1) return;

            if (clickedIndex == selectedItemPosition) {
                performItemAction(clickedIndex);
            } else {
                items[selectedItemPosition].setSelected(false);
                items[clickedIndex].setSelected(true);
                selectedItemPosition = clickedIndex;
            }
        };

        for (View item : items) {
            item.setOnClickListener(clickListener);
        }

        items[0].setSelected(true);
    }

    private void performItemAction(int index) {
        switch (index) {
            case 0: // 开机自启
                boolean bootChecked = !sw_boot.isChecked();
                sw_boot.setChecked(bootChecked);
                bootStartManager.toggleBoot(bootChecked, tv_boot_status);
                break;
            case 1: // 换台反转
                boolean reverseChecked = !sw_reverse.isChecked();
                sw_reverse.setChecked(reverseChecked);
                sp.edit().putBoolean("channel_reverse", reverseChecked).apply();
                Toast.makeText(context, "换台反转" + (reverseChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 2: // 画中画
                boolean pipChecked = !sw_pip.isChecked();
                sw_pip.setChecked(pipChecked);
                sp.edit().putBoolean("pip_enable", pipChecked).apply();
                if (pipChecked) {
                    Toast.makeText(context, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "画中画已关闭", Toast.LENGTH_SHORT).show();
                }
                break;
            case 3: // 频道线路
                showChannelLineDialog();
                break;
            case 4: // 解码器
                showDecoderModeDialog();
                break;
            case 5: // 渲染方式
                showRendererModeDialog();
                break;
            case 6: // 屏幕比例
                showRatioDialog();
                break;
            case 7: // 清晰度
                showResolutionDialog();
                break;
            case 8: // 重定向配置
                showRedirectConfigDialog();
                break;
            case 9: // 直播源订阅
                showSubscriptionDialog("live_history", "直播源订阅");
                break;
            case 10: // 节目单订阅
                showSubscriptionDialog("epg_history", "节目单订阅");
                break;
            case 11: // 检查更新
                updateManager.checkUpdate();
                break;
            case 12: // 版本信息
                showVersionInfoDialog();
                break;
            case 13: // 日志输出
                boolean logEnabled = sp.getBoolean("log_enable", false);
                boolean newState = !logEnabled;
                sp.edit().putBoolean("log_enable", newState).apply();
                tv_log_status.setText(newState ? "开启" : "关闭");
                Toast.makeText(context, "日志已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                MainActivity.toggleLogWindow(newState);
                break;
        }
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
        String sdkVersion = "Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")";
        String playerVersion = "androidx.media3 1.7.1";

        String message = "版本信息: v" + versionName + " (" + versionCode + ")\n\n" +
                "更新内容: \n" + updateNotes + "\n\n" +
                "UA: " + userAgent + "\n\n" +
                "SDK 版本: " + sdkVersion + "\n\n" +
                "播放器版本: " + playerVersion;

        android.text.SpannableString spannableString = new android.text.SpannableString(message);
        spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int startUc = message.indexOf("更新内容:");
        if (startUc != -1) {
            spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), startUc, startUc + 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.setPadding(24, 24, 24, 24);

        TextView titleView = new TextView(context);
        titleView.setText("📱 应用详情");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        layout.addView(titleView);

        TextView msgView = new TextView(context);
        msgView.setText(spannableString);
        msgView.setTextColor(Color.WHITE);
        msgView.setTextSize(16);
        msgView.setPadding(0, 16, 0, 0);
        layout.addView(msgView);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private int getCurrentLineIndex() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(context);
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "channel_line_index_" + channelKey;
            return sp.getInt(prefKey, 0);
        } else {
            return sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        }
    }

    private void showDarkSingleChoiceDialog(String title, String[] items, int checkedItem, Consumer<Integer> onSelected) {
        ListView listView = new ListView(context);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice, items) {
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

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.addView(titleView);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(context);
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(context, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
            return;
        }

        String channelKey = currentChannel.getChannelId();
        if (TextUtils.isEmpty(channelKey)) {
            channelKey = currentChannel.getName();
        }
        String prefKey = "channel_line_index_" + channelKey;
        int currentLineIndex = sp.getInt(prefKey, 0);

        List<String> lineList = new ArrayList<>();
        lineList.add("主源");
        for (int i = 1; i <= currentChannel.getBackupUrls().size(); i++) {
            lineList.add("源" + i);
        }
        String[] lineArray = lineList.toArray(new String[0]);

        showDarkSingleChoiceDialog("频道线路选择", lineArray, currentLineIndex, (which) -> {
            sp.edit().putInt(prefKey, which).apply();
            sp.edit().putInt(KEY_CHANNEL_LINE_INDEX, which).apply();

            tv_channel_line.setText(lineArray[which]);

            Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            Toast.makeText(context, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
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

    private void showResolutionDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(context);
        if (playerManager == null) return;
        List<String> resolutions = playerManager.getAvailableResolutions();
        if (resolutions.isEmpty()) {
            Toast.makeText(context, "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = resolutions.toArray(new String[0]);

        ListView listView = new ListView(context);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice, items) {
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

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedLabel = items[position];
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
                Toast.makeText(context, "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
            }
        });

        TextView titleView = new TextView(context);
        titleView.setText("选择清晰度");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setPadding(24, 24, 24, 0);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF272B3A);
        layout.addView(titleView);
        layout.addView(listView);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(layout)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }

    private void showSubscriptionDialog(String spKey, String title) {
        SourceManager sourceManager = new SourceManager(context, spKey);
        List<SourceManager.SourceItem> sources = sourceManager.getAllSources();

        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(
                new android.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
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
        tvIpAddress.setText(webServerManager.getAccessUrl());

        if (isLive) {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.VISIBLE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.VISIBLE);

            new Thread(() -> {
                android.graphics.Bitmap qrBitmap = null;
                try {
                    qrBitmap = qrCodeManager.createQR(webServerManager.getAccessUrl(), 240);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final android.graphics.Bitmap finalQrBitmap = qrBitmap;
                ((android.app.Activity) context).runOnUiThread(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            }).start();

            ivQrCode.setOnClickListener(v -> {
                Toast.makeText(context, "已生成二维码，请扫码", Toast.LENGTH_SHORT).show();
            });
            etName.setHint("请输入名称(选填)");
            etUrl.setHint("请输入地址");
        } else {
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.GONE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.GONE);
            etName.setHint("请输入节目单名称(选填)");
            etUrl.setHint("请输入EPG节目单地址");
        }

        int currentDefault = sourceManager.indexOfUrl(sourceManager.getDefaultUrl());
        SubscriptionAdapter adapter = new SubscriptionAdapter(context, sources);
        adapter.setSelectedPosition(currentDefault);

        adapter.setOnActionListener(new SubscriptionAdapter.OnActionListener() {
            @Override
            public void onSwitch(int position) {
                sourceManager.setDefault(position);

                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);

                Toast.makeText(context, "已切换到：" + sources.get(position).name, Toast.LENGTH_SHORT).show();
                adapter.setSelectedPosition(position);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDelete(int position) {
                if (position < 0 || position >= sources.size()) {
                    return;
                }
                SourceManager.SourceItem item = sources.get(position);

                AlertDialog deleteDialog = new AlertDialog.Builder(context)
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
                                Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(context, "删除失败，源未找到", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (sourceManager.addSource(name, url)) {
                etName.setText("");
                etUrl.setText("");
                sources.clear();
                sources.addAll(sourceManager.getAllSources());
                adapter.setSelectedPosition(sourceManager.indexOfUrl(sourceManager.getDefaultUrl()));
                adapter.notifyDataSetChanged();
                Toast.makeText(context, "已添加，正在刷新...", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent("com.tv.live.REFRESH_LIVE_AND_EPG");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            } else {
                Toast.makeText(context, "该地址已存在", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            etName.setText("");
            etUrl.setText("");
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
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
            Toast.makeText(context, "已设置", Toast.LENGTH_SHORT).show();
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
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            Toast.makeText(context, "已切换到" + modes[which] + "，正在重新加载…", Toast.LENGTH_SHORT).show();
        });
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
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);

            Toast.makeText(context, "已切换到" + modes[which] + "，正在应用……", Toast.LENGTH_SHORT).show();
        });
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
        int currentMax = sp.getInt(KEY_REDIRECT_MAX_COUNT, 5);
        boolean crossDomain = sp.getBoolean(KEY_REDIRECT_CROSS_DOMAIN, true);
        boolean crossProto = sp.getBoolean(KEY_REDIRECT_CROSS_PROTOCOL, true);
        boolean followHeader = sp.getBoolean(KEY_REDIRECT_FOLLOW_HEADERS, true);
        boolean ignoreSsl = sp.getBoolean(KEY_REDIRECT_IGNORE_SSL, false);
        boolean sendCookie = sp.getBoolean(KEY_REDIRECT_SEND_COOKIE, true);
        final String[] currentUaMode = {sp.getString(KEY_USER_AGENT_MODE, "exo")};

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

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        llUserAgent.setOnClickListener(v -> {
            final String[] uaOptions = {"ExoPlayer默认", "VLC"};
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

        AlertDialog dialog = new AlertDialog.Builder(context)
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
                } catch (Exception ignored) {
                    newMax = 5;
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
            Toast.makeText(context, "重定向配置保存成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    @Override
    public void dismiss() {
        super.dismiss();
        Intent unlockIntent = new Intent("com.tv.live.UNLOCK_SETTINGS");
        unlockIntent.setPackage(context.getPackageName());
        context.sendBroadcast(unlockIntent);
        if (webServerManager != null) webServerManager.stop();
        if (updateManager != null) updateManager.release();
    }
}
