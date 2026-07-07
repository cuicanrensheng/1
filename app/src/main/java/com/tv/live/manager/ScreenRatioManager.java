package com.tv.live.manager;

import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;

public class ScreenRatioManager {

    private final TVPlayerManager mPlayerManager;
    private final AppConfig appConfig;
    
    // 🟢【优化】缓存当前生效的比例，避免重复调用播放器
    private String currentAppliedRatio = "";

    public ScreenRatioManager(TVPlayerManager playerManager, AppConfig config) {
        this.mPlayerManager = playerManager;
        this.appConfig = config;
    }

    public void apply() {
        String ratio = appConfig.getScreenRatio();
        
        // 🟢【核心优化】如果设置的比例和当前应用的比例一致，直接跳过
        if (ratio.equals(currentAppliedRatio)) {
            return; 
        }
        
        switch (ratio) {
            case "原始":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FIT);
                break;
            case "填充":
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
            default: // 默认全屏/填充
                mPlayerManager.setScaleMode(TVPlayerManager.ScaleMode.FILL);
                break;
        }
        
        // 🟢 记录下本次生效的比例
        currentAppliedRatio = ratio;
    }
}
