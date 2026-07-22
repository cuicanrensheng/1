package com.tv.live;

import android.text.Spannable;
import android.text.SpannableString;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.List;
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
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页面 Activity（已修复：第一下聚焦，第二下确认，统一单次触发）
 */
public class SettingsActivity extends AppCompatActivity {
    // ====================== 控件声明 ======================
    private SwitchCompat sw_boot, sw_reverse, sw_pip, sw_together_watch;
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

    // 记录当前选中的设置项下标
    private int selectedItemPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

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
        sw_together_watch = findViewById(R.id.sw_together_watch);
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

        // ✅ 读取状态（只赋值，不触发点击）
        sw_boot.setChecked(sp.getBoolean("boot_auto_start", false));
        bootStartManager.updateBootStatusText(tv_boot_status);
        sw_reverse.setChecked(sp.getBoolean("channel_reverse", false));
        sw_pip.setChecked(sp.getBoolean("pip_enable", false));
        sw_together_watch.setChecked(sp.getBoolean("together_watch_enable", false));

        String decoderMode = sp.getString("decoder_mode", "auto");
        updateDecoderModeText(decoderMode);
        String rendererMode = sp.getString("renderer_type", "surface");
        updateRendererModeText(rendererMode);
        updateRedirectSettingText();
        
        // 初始化清晰度显示（按频道保存）
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        Channel currentChannel = playerManager.getCurrentChannel();
        String savedRes = "";
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "resolution_" + channelKey;
            savedRes = sp.getString(prefKey, "");
        } else {
            savedRes = sp.getString("resolution", "");
        }
        if (!savedRes.isEmpty()) {
            tv_resolution_status.setText(savedRes);
        } else {
            tv_resolution_status.setText("自动");
        }

        tv_channel_line = findViewById(R.id.tv_channel_line);
        int currentLineIndex = 0;
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "channel_line_index_" + channelKey;
            currentLineIndex = sp.getInt(prefKey, 0);
        } else {
            currentLineIndex = sp.getInt(KEY_CHANNEL_LINE_INDEX, 0);
        }
        tv_channel_line.setText(getLineName(currentLineIndex));

        // ✅ 初始化列表，统一接管所有点击和焦点事件
        initSettingsItemList();

        // ✅ 启动 Web 服务等
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

    // ================================================================
    // ✅ 核心修改：统一管理所有设置项的点击与焦点
    // ================================================================
    private void initSettingsItemList() {
        // 1. 把所有设置项放入数组（包括16个元素，涵盖所有）
        View[] items = {
            findViewById(R.id.item_boot),
            findViewById(R.id.item_reverse),
            findViewById(R.id.item_pip),
            findViewById(R.id.item_together_watch),
            findViewById(R.id.item_channel_line),
            findViewById(R.id.item_resolution),
            findViewById(R.id.item_decoder),
            findViewById(R.id.item_renderer),
            findViewById(R.id.tv_screen_ratio),
            findViewById(R.id.item_redirect),
            findViewById(R.id.item_live_subscribe),
            findViewById(R.id.item_epg_subscribe),
            findViewById(R.id.item_check_update),
            findViewById(R.id.item_version_info),
            findViewById(R.id.item_log)
        };

        // 2. 统一设置背景选择器
        for (View item : items) {
            item.setBackgroundResource(R.drawable.item_settings_bg);
            item.setFocusable(true);
            item.setClickable(true);
        }

        // 3. 遥控器焦点监听器：仅移动高亮（聚焦），不触发执行
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

        // 4. 统一点击/确认监听器：第一下聚焦，第二下确认
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
                // ✅ 第二次点击（确认）：执行功能
                performItemAction(clickedIndex);
            } else {
                // ✅ 第一次点击（聚焦）：只移动高亮，不执行
                items[selectedItemPosition].setSelected(false);
                items[clickedIndex].setSelected(true);
                selectedItemPosition = clickedIndex;
            }
        };

        // 5. 绑定点击事件（保证每个控件只绑定这一次）
        for (View item : items) {
            item.setOnClickListener(clickListener);
        }

        // 6. 默认选中第一项并请求焦点
        items[0].setSelected(true);
        
        // 确保ScrollView不拦截焦点
        if (scrollView != null) {
            scrollView.setFocusable(false);
            scrollView.setFocusableInTouchMode(false);
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            scrollView.scrollTo(0, 0);
        }
        
        // 延迟请求焦点，确保布局完成
        mainHandler.postDelayed(() -> {
            items[0].requestFocus();
            android.util.Log.d("Settings", "First item focused");
        }, 100);
    }

    // ✅ 根据选中的下标执行对应的功能
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
                Toast.makeText(this, "换台反转" + (reverseChecked ? "已开启" : "已关闭"), Toast.LENGTH_SHORT).show();
                break;
            case 2: // 画中画
                boolean pipChecked = !sw_pip.isChecked();
                sw_pip.setChecked(pipChecked);
                sp.edit().putBoolean("pip_enable", pipChecked).apply();
                if (pipChecked) {
                    Toast.makeText(this, "画中画已开启，按Home键自动小窗播放", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "画中画已关闭", Toast.LENGTH_SHORT).show();
                }
                break;
            case 3: // 一起看开关
                boolean twChecked = !sw_together_watch.isChecked();
                sw_together_watch.setChecked(twChecked);
                sp.edit().putBoolean("together_watch_enable", twChecked).apply();
                if (twChecked) {
                    Toast.makeText(this, "一起看已开启", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "一起看已关闭", Toast.LENGTH_SHORT).show();
                }
                MainActivity mainActivity = MainActivity.getRunningInstance();
                if (mainActivity != null) {
                    android.util.Log.d("SettingsActivity", "找到MainActivity实例，调用toggleTogetherWatch:" + twChecked);
                    mainActivity.toggleTogetherWatch(twChecked);
                } else {
                    android.util.Log.d("SettingsActivity", "未找到MainActivity实例");
                }
                break;
            case 4: // 频道线路
                showChannelLineDialog();
                break;
            case 5: // 清晰度选择
                showResolutionDialog();
                break;
            case 6: // 解码器
                showDecoderModeDialog();
                break;
            case 7: // 渲染方式
                showRendererModeDialog();
                break;
            case 8: // 屏幕比例
                showRatioDialog();
                break;
            case 9: // 重定向配置
                showRedirectConfigDialog();
                break;
            case 10: // 直播源订阅
                showSubscriptionDialog("live_history", "直播源订阅");
                break;
            case 11: // 节目单订阅
                showSubscriptionDialog("epg_history", "节目单订阅");
                break;
            case 12: // 检查更新
                updateManager.checkUpdate();
                break;
            case 13: // 版本信息
                showVersionInfoDialog();
                break;
            case 14: // 日志输出
                boolean logEnabled = sp.getBoolean("log_enable", false);
                boolean newState = !logEnabled;
                sp.edit().putBoolean("log_enable", newState).apply();
                tv_log_status.setText(newState ? "开启" : "关闭");
                Toast.makeText(SettingsActivity.this, "日志已" + (newState ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
                MainActivity.toggleLogWindow(newState);
                break;
        }
    }

    // ================================================================
    // 以下为原有功能代码，全部保留
    // ================================================================

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

        SpannableString spannableString = new SpannableString(message);
        spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int startUc = message.indexOf("更新内容:");
        if (startUc != -1) {
            spannableString.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), startUc, startUc + 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

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
        msgView.setText(spannableString);
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

    private String getLineName(int index) {
        if (index == 0) return "主源";
        return "源" + index;
    }

    private void showDarkSingleChoiceDialog(String title, String[] items, int checkedItem, java.util.function.Consumer<Integer> onSelected) {
        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        final int[] pendingPos = {checkedItem};

        class CustomAdapter extends ArrayAdapter<String> {
            private int selectedPos;

            public CustomAdapter(android.content.Context context, String[] items, int initialPos) {
                super(context, android.R.layout.simple_list_item_single_choice, items);
                selectedPos = initialPos;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(16, 16, 16, 16);
                
                if (position == selectedPos) {
                    tv.setTextColor(0xFF40A9FF);
                    view.setBackgroundColor(0x3340A9FF);
                } else {
                    tv.setTextColor(Color.WHITE);
                    view.setBackgroundColor(0x00000000);
                }
                return view;
            }

            public void setSelectedPos(int pos) {
                selectedPos = pos;
                notifyDataSetChanged();
            }
        }
        
        CustomAdapter adapter = new CustomAdapter(this, items, checkedItem);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(checkedItem, true);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (pendingPos[0] == position) {
                onSelected.accept(position);
            } else {
                pendingPos[0] = position;
                adapter.setSelectedPos(position);
                listView.setItemChecked(position, true);
            }
        });

        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                pendingPos[0] = position;
                adapter.setSelectedPos(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
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

    private void showChannelLineDialog() {
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel == null) {
            Toast.makeText(this, "请先播放一个频道，再切换线路", Toast.LENGTH_SHORT).show();
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

            if (playerManager != null && currentChannel != null) {
                String playUrl;
                if (which == 0) {
                    playUrl = currentChannel.getMainPlayUrl();
                } else {
                    List<String> backups = currentChannel.getBackupUrls();
                    int backupIndex = which - 1;
                    if (backupIndex >= 0 && backupIndex < backups.size()) {
                        playUrl = backups.get(backupIndex);
                    } else {
                        playUrl = currentChannel.getMainPlayUrl();
                    }
                }
                playerManager.playUrl(playUrl, currentChannel.getName(), currentChannel);
            }

            Toast.makeText(SettingsActivity.this, "已切换到：" + lineArray[which], Toast.LENGTH_SHORT).show();
        });
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

    private void showResolutionDialog() {
        android.util.Log.d("ResolutionDialog", "showResolutionDialog called");
        TVPlayerManager playerManager = TVPlayerManager.getInstance(this);
        if (playerManager == null) {
            android.util.Log.d("ResolutionDialog", "playerManager is null");
            return;
        }
        List<String> resolutions = playerManager.getAvailableResolutions();
        android.util.Log.d("ResolutionDialog", "Available resolutions: " + resolutions);
        if (resolutions.isEmpty()) {
            Toast.makeText(this, "当前直播源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = resolutions.toArray(new String[0]);

        ListView listView = new ListView(this);
        listView.setBackgroundColor(0xFF272B3A);
        listView.setDivider(new ColorDrawable(0x33FFFFFF));
        listView.setDividerHeight(1);
        listView.setPadding(0, 16, 0, 16);

        // 获取当前清晰度（优先从SharedPreferences读取）
        // 按频道读取保存的清晰度
        String savedRes = "";
        Channel currentChannel = playerManager.getCurrentChannel();
        if (currentChannel != null) {
            String channelKey = currentChannel.getChannelId();
            if (TextUtils.isEmpty(channelKey)) {
                channelKey = currentChannel.getName();
            }
            String prefKey = "resolution_" + channelKey;
            savedRes = sp.getString(prefKey, "");
        } else {
            savedRes = sp.getString("resolution", "");
        }
        String currentResLabel = savedRes.isEmpty() ? playerManager.getCurrentResolutionLabel() : savedRes;
        android.util.Log.d("ResolutionDialog", "savedRes: '" + savedRes + "', currentResLabel: '" + currentResLabel + "'");
        int initialPos = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(currentResLabel)) {
                initialPos = i;
                break;
            }
        }
        android.util.Log.d("ResolutionDialog", "initialPos: " + initialPos);

        class CustomAdapter extends ArrayAdapter<String> {
            private int selectedPos;

            public CustomAdapter(android.content.Context context, String[] items, int initialPos) {
                super(context, android.R.layout.simple_list_item_single_choice, items);
                selectedPos = initialPos;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(16, 16, 16, 16);
                
                if (position == selectedPos) {
                    tv.setTextColor(0xFF40A9FF);
                    view.setBackgroundColor(0x3340A9FF);
                } else {
                    tv.setTextColor(Color.WHITE);
                    view.setBackgroundColor(0x00000000);
                }
                return view;
            }

            public void setSelectedPos(int pos) {
                selectedPos = pos;
                notifyDataSetChanged();
            }
        }
        
        CustomAdapter adapter = new CustomAdapter(this, items, initialPos);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setItemChecked(initialPos, true);
        listView.setSelection(initialPos);

        final int[] pendingPos = {initialPos};

        listView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                android.util.Log.d("ResolutionDialog", "onItemSelected position: " + position + ", focusedPos: " + position);
                pendingPos[0] = position;
                adapter.setSelectedPos(position);
                listView.setItemChecked(position, true);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                android.util.Log.d("ResolutionDialog", "onNothingSelected");
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            android.util.Log.d("ResolutionDialog", "onItemClick position: " + position + ", pendingPos[0]: " + pendingPos[0]);
            if (pendingPos[0] == position) {
                String selectedLabel = items[position];
                android.util.Log.d("ResolutionDialog", "Confirmed selection: " + selectedLabel);
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
                    // 按频道保存清晰度设置
                    if (currentChannel != null) {
                        String channelKey = currentChannel.getChannelId();
                        if (TextUtils.isEmpty(channelKey)) {
                            channelKey = currentChannel.getName();
                        }
                        String prefKey = "resolution_" + channelKey;
                        sp.edit().putString(prefKey, selectedLabel).apply();
                    } else {
                        sp.edit().putString("resolution", selectedLabel).apply();
                    }
                    tv_resolution_status.setText(selectedLabel);
                    Toast.makeText(SettingsActivity.this, "已切换至: " + selectedLabel, Toast.LENGTH_SHORT).show();
                }
            } else {
                pendingPos[0] = position;
                adapter.setSelectedPos(position);
                listView.setItemChecked(position, true);
            }
        });

        TextView titleView = new TextView(this);
        titleView.setText("选择清晰度");
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
            if (tvDialogTitle != null) tvDialogTitle.setText(title);
            if (llScanHeader != null) llScanHeader.setVisibility(View.VISIBLE);
            if (ivQrCode != null) ivQrCode.setVisibility(View.VISIBLE);
            
            new Thread(() -> {
                Bitmap qrBitmap = null;
                try {
                    qrBitmap = qrCodeManager.createQR(currentWebUrl, 240);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final Bitmap finalQrBitmap = qrBitmap;
                runOnUiThread(() -> {
                    if (finalQrBitmap != null) {
                        ivQrCode.setImageBitmap(finalQrBitmap);
                    } else {
                        ivQrCode.setBackgroundColor(Color.LTGRAY);
                    }
                });
            }).start();

            ivQrCode.setOnClickListener(v -> {
                Toast.makeText(SettingsActivity.this, "已生成二维码，请扫码", Toast.LENGTH_SHORT).show();
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

        etName.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                lvSourceList.requestFocus();
                return true;
            }
            return false;
        });
        etUrl.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                lvSourceList.requestFocus();
                return true;
            }
            return false;
        });

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
                if (position < 0 || position >= sources.size()) {
                    return;
                }
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
        if (lvSourceList != null) {
            lvSourceList.requestFocus();
        }
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
        Button btnCancel = dialogView.findViewById(R.id.btn_redirect_cancel);
        Button btnSave = dialogView.findViewById(R.id.btn_redirect_save);

        LinearLayout llCrossDomain = dialogView.findViewById(R.id.ll_cross_domain);
        LinearLayout llCrossProto = dialogView.findViewById(R.id.ll_cross_proto);
        LinearLayout llFollowHeader = dialogView.findViewById(R.id.ll_follow_header);
        LinearLayout llSendCookie = dialogView.findViewById(R.id.ll_send_cookie);
        LinearLayout llIgnoreSsl = dialogView.findViewById(R.id.ll_ignore_ssl);
        TextView tvCrossDomain = dialogView.findViewById(R.id.tv_cross_domain);
        TextView tvCrossProto = dialogView.findViewById(R.id.tv_cross_proto);
        TextView tvFollowHeader = dialogView.findViewById(R.id.tv_follow_header);
        TextView tvSendCookie = dialogView.findViewById(R.id.tv_send_cookie);
        TextView tvIgnoreSsl = dialogView.findViewById(R.id.tv_ignore_ssl);

        tvUserAgentStatus.setText("exo".equals(currentUaMode[0]) ? "ExoPlayer默认" : "VLC");
        etMax.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        etMax.setText(String.valueOf(currentMax));
        etMax.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                llCrossDomain.requestFocus();
                return true;
            }
            return false;
        });
        swCrossDomain.setChecked(crossDomain);
        swCrossProto.setChecked(crossProto);
        swFollowHeader.setChecked(followHeader);
        swIgnoreSsl.setChecked(ignoreSsl);
        swSendCookie.setChecked(sendCookie);

        final int[] pendingPos = {0};
        final LinearLayout[] items = {llCrossDomain, llCrossProto, llFollowHeader, llSendCookie, llIgnoreSsl, llUserAgent};
        final String[] itemNames = {"crossDomain", "crossProto", "followHeader", "sendCookie", "ignoreSsl", "userAgent"};

        llCrossDomain.setBackgroundColor(0x3340A9FF);
        tvCrossDomain.setTextColor(0xFF40A9FF);

        View.OnClickListener clickListener = v -> {
            int currentPos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    currentPos = i;
                    break;
                }
            }

            android.util.Log.d("KeyboardDebug", "Click detected on: " + (currentPos >= 0 ? itemNames[currentPos] : "unknown") + ", pendingPos=" + pendingPos[0] + ", currentPos=" + currentPos);

            if (pendingPos[0] == currentPos) {
                android.util.Log.d("KeyboardDebug", "Second click - confirming action for: " + itemNames[currentPos]);
                if (currentPos == 0) {
                    swCrossDomain.setChecked(!swCrossDomain.isChecked());
                    android.util.Log.d("KeyboardDebug", "Switched crossDomain to: " + swCrossDomain.isChecked());
                } else if (currentPos == 1) {
                    swCrossProto.setChecked(!swCrossProto.isChecked());
                    android.util.Log.d("KeyboardDebug", "Switched crossProto to: " + swCrossProto.isChecked());
                } else if (currentPos == 2) {
                    swFollowHeader.setChecked(!swFollowHeader.isChecked());
                    android.util.Log.d("KeyboardDebug", "Switched followHeader to: " + swFollowHeader.isChecked());
                } else if (currentPos == 3) {
                    swSendCookie.setChecked(!swSendCookie.isChecked());
                    android.util.Log.d("KeyboardDebug", "Switched sendCookie to: " + swSendCookie.isChecked());
                } else if (currentPos == 4) {
                    swIgnoreSsl.setChecked(!swIgnoreSsl.isChecked());
                    android.util.Log.d("KeyboardDebug", "Switched ignoreSsl to: " + swIgnoreSsl.isChecked());
                } else if (currentPos == 5) {
                    android.util.Log.d("KeyboardDebug", "Opening UA dialog");
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
                }
            } else {
                android.util.Log.d("KeyboardDebug", "First click - moving highlight to: " + itemNames[currentPos]);
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0xFF333545);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = currentPos;
                android.util.Log.d("KeyboardDebug", "pendingPos updated to: " + currentPos);
            }
        };

        View.OnKeyListener keyListener = (v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                android.util.Log.d("KeyboardDebug", "Key down: keyCode=" + keyCode + ", view=" + v.getClass().getSimpleName());
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    android.util.Log.d("KeyboardDebug", "Enter/Center key pressed on focused view");
                    return false;
                }
            }
            return false;
        };

        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            int pos = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == v) {
                    pos = i;
                    break;
                }
            }
            android.util.Log.d("KeyboardDebug", "Focus change: " + (pos >= 0 ? itemNames[pos] : "unknown") + ", hasFocus=" + hasFocus);
            if (hasFocus && pos >= 0) {
                android.util.Log.d("KeyboardDebug", "Focus moved, updating highlight to: " + itemNames[pos]);
                for (int i = 0; i < items.length; i++) {
                    items[i].setBackgroundColor(0xFF333545);
                    for (int j = 0; j < items[i].getChildCount(); j++) {
                        View child = items[i].getChildAt(j);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(Color.WHITE);
                        }
                    }
                }
                v.setBackgroundColor(0x3340A9FF);
                for (int i = 0; i < ((LinearLayout) v).getChildCount(); i++) {
                    View child = ((LinearLayout) v).getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(0xFF40A9FF);
                    }
                }
                pendingPos[0] = pos;
                android.util.Log.d("KeyboardDebug", "pendingPos updated via focus to: " + pos);
            }
        };

        llCrossDomain.setOnClickListener(clickListener);
        llCrossDomain.setOnKeyListener(keyListener);
        llCrossDomain.setOnFocusChangeListener(focusListener);
        llCrossProto.setOnClickListener(clickListener);
        llCrossProto.setOnKeyListener(keyListener);
        llCrossProto.setOnFocusChangeListener(focusListener);
        llFollowHeader.setOnClickListener(clickListener);
        llFollowHeader.setOnKeyListener(keyListener);
        llFollowHeader.setOnFocusChangeListener(focusListener);
        llSendCookie.setOnClickListener(clickListener);
        llSendCookie.setOnKeyListener(keyListener);
        llSendCookie.setOnFocusChangeListener(focusListener);
        llIgnoreSsl.setOnClickListener(clickListener);
        llIgnoreSsl.setOnKeyListener(keyListener);
        llIgnoreSsl.setOnFocusChangeListener(focusListener);
        llUserAgent.setOnClickListener(clickListener);
        llUserAgent.setOnKeyListener(keyListener);
        llUserAgent.setOnFocusChangeListener(focusListener);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        llCrossDomain.requestFocus();

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
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
            dialog.dismiss();
        });
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

            android.util.Log.d("Settings", "onWindowFocusChanged: hasFocus=true");
            
            View[] items = {
                findViewById(R.id.item_boot),
                findViewById(R.id.item_reverse),
                findViewById(R.id.item_pip),
                findViewById(R.id.item_together_watch),
                findViewById(R.id.item_channel_line),
                findViewById(R.id.item_resolution),
                findViewById(R.id.item_decoder),
                findViewById(R.id.item_renderer),
                findViewById(R.id.tv_screen_ratio),
                findViewById(R.id.item_redirect),
                findViewById(R.id.item_live_subscribe),
                findViewById(R.id.item_epg_subscribe),
                findViewById(R.id.item_check_update),
                findViewById(R.id.item_version_info),
                findViewById(R.id.item_log)
            };
            
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null) {
                    items[i].setSelected(i == selectedItemPosition);
                }
            }
            
            if (selectedItemPosition >= 0 && selectedItemPosition < items.length && items[selectedItemPosition] != null) {
                items[selectedItemPosition].requestFocus();
                android.util.Log.d("Settings", "onWindowFocusChanged: requestFocus to position " + selectedItemPosition);
            }
            
            if (scrollView != null && selectedItemPosition >= 0 && selectedItemPosition < items.length && items[selectedItemPosition] != null) {
                items[selectedItemPosition].post(() -> {
                    int scrollY = items[selectedItemPosition].getTop() - scrollView.getHeight() / 2;
                    scrollView.smoothScrollTo(0, Math.max(0, scrollY));
                });
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        android.util.Log.d("Settings", "onKeyDown keyCode:" + keyCode);
        
        View[] items = {
            findViewById(R.id.item_boot),
            findViewById(R.id.item_reverse),
            findViewById(R.id.item_pip),
            findViewById(R.id.item_together_watch),
            findViewById(R.id.item_channel_line),
            findViewById(R.id.item_resolution),
            findViewById(R.id.item_decoder),
            findViewById(R.id.item_renderer),
            findViewById(R.id.tv_screen_ratio),
            findViewById(R.id.item_redirect),
            findViewById(R.id.item_live_subscribe),
            findViewById(R.id.item_epg_subscribe),
            findViewById(R.id.item_check_update),
            findViewById(R.id.item_version_info),
            findViewById(R.id.item_log)
        };
        
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int nextPos = selectedItemPosition + 1;
            if (nextPos < items.length && items[nextPos] != null) {
                items[nextPos].requestFocus();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            int prevPos = selectedItemPosition - 1;
            if (prevPos >= 0 && items[prevPos] != null) {
                items[prevPos].requestFocus();
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            performItemAction(selectedItemPosition);
            return true;
        }
        
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        android.util.Log.d("Settings", "onKeyUp keyCode:" + keyCode);
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        android.util.Log.d("Settings", "dispatchKeyEvent keyCode:" + event.getKeyCode() + ", action:" + event.getAction());
        
        View[] items = {
            findViewById(R.id.item_boot),
            findViewById(R.id.item_reverse),
            findViewById(R.id.item_pip),
            findViewById(R.id.item_together_watch),
            findViewById(R.id.item_channel_line),
            findViewById(R.id.item_resolution),
            findViewById(R.id.item_decoder),
            findViewById(R.id.item_renderer),
            findViewById(R.id.tv_screen_ratio),
            findViewById(R.id.item_redirect),
            findViewById(R.id.item_live_subscribe),
            findViewById(R.id.item_epg_subscribe),
            findViewById(R.id.item_check_update),
            findViewById(R.id.item_version_info),
            findViewById(R.id.item_log)
        };
        
        // Find current position based on focused view
        int currentPos = 0;
        boolean foundFocused = false;
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && items[i].isFocused()) {
                currentPos = i;
                foundFocused = true;
                break;
            }
        }
        
        if (!foundFocused) {
            android.util.Log.d("Settings", "WARNING: No focused item found!");
            View focusedView = getCurrentFocus();
            if (focusedView != null) {
                android.util.Log.d("Settings", "Current focus is: " + focusedView.getClass().getName());
                android.util.Log.d("Settings", "Current focus ID: " + focusedView.getId());
            } else {
                android.util.Log.d("Settings", "Current focus is null!");
            }
        }
        
        selectedItemPosition = currentPos;
        
        android.util.Log.d("Settings", "Current position: " + currentPos);
        
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int nextPos = currentPos + 1;
                if (nextPos < items.length && items[nextPos] != null) {
                    items[currentPos].setSelected(false);
                    items[nextPos].setSelected(true);
                    selectedItemPosition = nextPos;
                    items[nextPos].requestFocus();
                    android.util.Log.d("Settings", "Focus moved down to position: " + nextPos);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                int prevPos = currentPos - 1;
                if (prevPos >= 0 && items[prevPos] != null) {
                    items[currentPos].setSelected(false);
                    items[prevPos].setSelected(true);
                    selectedItemPosition = prevPos;
                    items[prevPos].requestFocus();
                    android.util.Log.d("Settings", "Focus moved up to position: " + prevPos);
                    return true;
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                performItemAction(currentPos);
                android.util.Log.d("Settings", "Performed action at position: " + currentPos);
                return true;
            }
        }
        
        return super.dispatchKeyEvent(event);
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
        mainHandler.removeCallbacksAndMessages(null);

        Intent unlockIntent = new Intent("com.tv.live.UNLOCK_SETTINGS");
        unlockIntent.setPackage(getPackageName());
        sendBroadcast(unlockIntent);
    }
}
