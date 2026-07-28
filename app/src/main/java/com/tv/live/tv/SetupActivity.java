package com.tv.live.tv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

public class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        final EditText etLive = new EditText(this);
        etLive.setHint("请输入直播源 M3U 地址");
        etLive.setSingleLine(true);

        final EditText etEpg = new EditText(this);
        etEpg.setHint("请输入 EPG 地址");
        etEpg.setSingleLine(true);

        Button btnSave = new Button(this);
        btnSave.setText("保存并返回");

        layout.addView(etLive);
        layout.addView(etEpg);
        layout.addView(btnSave);
        setContentView(layout);

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String live = etLive.getText().toString().trim();
                String epg = etEpg.getText().toString().trim();

                SharedPreferences sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sp.edit();
                if (!live.isEmpty()) {
                    editor.putString("custom_live_url", live);
                }
                if (!epg.isEmpty()) {
                    editor.putString("custom_epg_url", epg);
                }
                editor.apply();

                Toast.makeText(SetupActivity.this, "配置已保存，返回主页后请手动切换信号源", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
