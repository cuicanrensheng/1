package com.iptvlive.activity;
import com.iptvlive.R;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.iptvlive.bean.EpgInfoBean;
import com.iptvlive.receiver.RefreshSubReceiver;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.EpgTimeUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * INFO弹窗EPG节目列表
 */
public class EpgDialog extends Dialog {
    private Context mCtx;
    private String chName;
    private ListView lvEpg;

    // 单参构造（兼容系统规范）
    public EpgDialog(Context context) {
        this(context, "");
    }

    // 原有双参构造保留
    public EpgDialog(Context context, String name) {
        super(context);
        mCtx = context;
        chName = name;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_epg);
        lvEpg = findViewById(R.id.lv_epg);
        loadEpg();
    }

    private void loadEpg() {
        List<String> showList = new ArrayList<>();
        List<EpgInfoBean> allEpg = RefreshSubReceiver.globalEpgList;
        String today = EpgTimeUtil.getNowYMD();
        boolean onlyToday = AppSpUtil.getEpgOnlyToday();
        if (allEpg == null || allEpg.isEmpty()) {
            showList.add("暂无EPG数据，请WEB配置EPG地址");
        } else {
            for (EpgInfoBean item : allEpg) {
                String day = item.startTime.substring(0, 10);
                if (onlyToday && !day.equals(today)) continue;
                String s = item.startTime.substring(8, 10) + ":" + item.startTime.substring(10, 12);
                String e = item.endTime.substring(8, 10) + ":" + item.endTime.substring(10, 12);
                showList.add(s + "~" + e + " " + item.proName);
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(mCtx, android.R.layout.simple_list_item_1, showList);
        lvEpg.setAdapter(adapter);
    }
}
