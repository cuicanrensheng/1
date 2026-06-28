package com.tv.live.manager;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.tv.live.SettingsActivity;
import java.lang.ref.WeakReference;

/**
 * 显示管理器【内存泄漏修复版】
 * 统一管理全面屏、加载弹窗
 */
public class DisplayManager {
    // 修复：弱引用存储Activity，取消强持有页面
    private final WeakReference<Activity> activityRef;

    private View loadingView;
    private TextView tvLoadingText;
    private boolean loadingViewInitialized = false;

    // 构造传入Activity，包装弱引用
    public DisplayManager(Activity activity) {
        this.activityRef = new WeakReference<>(activity);
    }

    // 统一获取Activity并判空
    private Activity getActivity() {
        return activity != null ? activityRef.get() : null;
    }

    // 应用全面屏适配
    public void applyFullScreen() {
        Activity activity = getActivity();
        if (activity == null) {
            SettingsActivity.logOperation("【适配】页面已销毁，跳过全屏");
            return;
        }
        try {
            // 刘海屏适配 Android P+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                activity.getWindow().setAttributes(lp);
            }
            // 全屏Flag
            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            // Android 10及以下旧沉浸式
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }
            // Android 11+ 新窗口控制API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
                activity.getWindow().setDecorFitsSystemWindows(false);
            }
            SettingsActivity.logOperation("【适配】全面屏适配成功");
        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.logOperation("【适配】全面屏适配失败：" + e.getMessage());
        }
    }

    // 页面焦点变化重新全屏
    public void reapplyFullScreen() {
        applyFullScreen();
    }

    // 动态创建加载弹窗
    private void initLoadingView() {
        Activity activity = getActivity();
        if (activity == null || loadingViewInitialized) return;
        try {
            FrameLayout rootLayout = activity.findViewById(android.R.id.content);
            // 外层遮罩
            FrameLayout loadingLayout = new FrameLayout(activity);
            loadingLayout.setBackgroundColor(0xEE000000);
            loadingLayout.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            loadingLayout.setVisibility(View.GONE);

            // 垂直居中布局
            Linear linearLayout = new LinearLayout(activity);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            linearLayout.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            llParams.gravity = Gravity.CENTER;
            linearLayout.setLayoutParams(llParams);

            ProgressBar progressBar = new ProgressBar(activity);
            linearLayout.addView(progressBar);

            tvLoadingText = new TextView(activity);
            tvLoadingText.setText("加载中...");
            tvLoadingText.setTextColor(Color.WHITE);
            tvLoadingText.setTextSize(16);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.setMargins(0, 20, 0, 0);
            tvLoadingText.setLayoutParams(textParams);
            linearLayout.addView(tvLoadingText);

            loadingLayout.addView(linearLayout);
            rootLayout.addView(loadingLayout);
            loadingView = loadingLayout;
            loadingViewInitialized = true;
            SettingsActivity.logOperation("【加载】加载视图初始化完成");
        } catch (Exception e) {
            e.printStackTrace();
            SettingsActivity.logOperation("【加载】加载视图初始化失败：" + e.getMessage());
        }
    }

    public void showLoading(String text) {
        if (!loadingViewInitialized) initLoadingView();
        if (loadingView != null) loadingView.setVisibility(View.VISIBLE);
        if (tvLoadingText != null && text != null) tvLoadingText.setText(text);
        SettingsActivity.logOperation("【加载】显示加载动画：" + text);
    }

    public void showLoading() {
        showLoading("加载中...");
    }

    public void updateLoadingText(String text) {
        if (tvLoadingText != null && text != null) tvLoadingText.setText(text);
    }

    public void hideLoading() {
        if (loadingView != null) loadingView.setVisibility(View.GONE);
        SettingsActivity.logOperation("【加载】隐藏加载动画");
    }

    public boolean isLoadingShowing() {
        return loadingView != null && loadingView.getVisibility() == View.VISIBLE;
    }

    // ✅ 规范完整release()，满足全部清理要求
    public void release() {
        // 1. 移除加载View，解除父布局绑定
        if (loadingView != null && loadingView.getParent() != null) {
            try {
                ((ViewGroup) loadingView.getParent()).removeView(loadingView);
            } catch (Exception ignore) {}
        }
        // 2. 置空所有视图资源
        loadingView = null;
        tvLoadingText = null;
        loadingViewInitialized = false;

        // 3. 清空Activity弱引用
        if (activityRef != null) {
            activityRef.clear();
        }
    }
}
