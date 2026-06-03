package com.iptvlive.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.iptvlive.httpserver.LocalHttpServer;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.AutoRefreshUtil;
import com.iptvlive.util.HttpHeaderSpUtil;
import com.iptvlive.util.LogSpUtil;
import java.io.File;

/**
 * 设置页面：Header配置、WEB服务、日志、清除缓存、自动刷新开关
 */
public class SettingActivity extends AppCompatActivity {
    private EditText etUa, etRef, etCk;
    private CheckBox cbAutoRefresh;
    private LocalHttpServer webServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        bindView();
        //回填已有配置
        etUa.setText(HttpHeaderSpUtil.getUA());
        etRef.setText(HttpHeaderSpUtil.getReferer());
        etCk.setText(HttpHeaderSpUtil.getCookie());
        cbAutoRefresh.setChecked(AppSpUtil.getAutoRefreshSub());
    }

    private void bindView() {
        etUa = findViewById(R.id.et_ua);
        etRef = findViewById(R.id.et_ref);
        etCk = findViewById(R.id.et_ck);
        cbAutoRefresh = findViewById(R.id.cb_auto_refresh);

        Button btnSaveHeader = findViewById(R.id.btn_save_header);
        Button btnOpenWeb = findViewById(R.id.btn_open_web);
        Button btnParseLog = findViewById(R.id.btn_parse_log);
        Button btnOptLog = findViewById(R.id.btn_opt_log);
        Button btnClearCache = findViewById(R.id.btn_clear_cache);

        //保存Header
        btnSaveHeader.setOnClickListener(v -> {
            HttpHeaderSpUtil.setUA(etUa.getText().toString().trim());
            HttpHeaderSpUtil.setReferer(etRef.getText().toString().trim());
            HttpHeaderSpUtil.setCookie(etCk.getText().toString().trim());
            Toast.makeText(this, "Header保存成功", Toast.LENGTH_SHORT).show();
        });

        //启动WEB 10481
        btnOpenWeb.setOnClickListener(v -> {
            try {
                if (webServer == null) {
                    webServer = new LocalHttpServer(10481, this);
                    webServer.start();
                }
                Toast.makeText(this, "WEB启动成功，同局域网访问：本机IP:10481", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "启动失败:" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        //查看解析日志
        btnParseLog.setOnClickListener(v -> {
            String log = String.join("\n", LogSpUtil.getParseLogList());
            new AlertDialog.Builder(this).setMessage(log).show();
        });

        //查看操作日志
        btnOptLog.setOnClickListener(v -> {
            String log = String.join("\n", LogSpUtil.getOperCrashLogList());
            new AlertDialog.Builder(this).setMessage(log).show();
        });

        //删除EPG缓存
        btnClearCache.setOnClickListener(v -> {
            File cache = new File(getFilesDir(), "epg_cache.xml");
            if (cache.exists()) cache.delete();
            Toast.makeText(this, "EPG缓存已清除", Toast.LENGTH_SHORT).show();
        });

        //自动刷新勾选
        cbAutoRefresh.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppSpUtil.setAutoRefreshSub(isChecked);
            if (isChecked) {
                AutoRefreshUtil.startRefreshTask(this, AppSpUtil.getAutoRefreshHour());
                Toast.makeText(this, "开启定时刷新", Toast.LENGTH_SHORT).show();
            } else {
                AutoRefreshUtil.stopRefreshTask(this);
                Toast.makeText(this, "关闭定时刷新", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webServer != null && webServer.isAlive()) {
            webServer.stop();
        }
    }
}
