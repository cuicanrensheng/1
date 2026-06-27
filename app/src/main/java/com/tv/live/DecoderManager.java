package com.tv.live;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解码器管理类
 * 负责解码器模式切换、系统解码器检测、解码器选择器创建、广播监听等核心逻辑
 */
public class DecoderManager {
    private static final String TAG = "DecoderManager";

    // 解码器模式常量
    public static final int DECODER_MODE_AUTO = 0;
    public static final int DECODER_MODE_HARD = 1;
    public static final int DECODER_MODE_SOFT = 2;

    // 广播Action常量
    public static final String ACTION_DECODER_MODE_CHANGED = "com.tv.live.DECODER_MODE_CHANGED";
    // SharedPreferences相关常量
    private static final String SP_NAME = "app_settings";
    private static final String KEY_DECODER_MODE = "decoder_mode";

    private static DecoderManager instance;
    private Context mContext;

    private int mCurrentDecoderMode = DECODER_MODE_AUTO;
    private OnDecoderModeChangeListener mModeChangeListener;
    private DecoderModeBroadcastReceiver mBroadcastReceiver;
    private boolean mIsReceiverRegistered = false;

    /**
     * 单例获取方法
     * @param context 上下文（自动转为ApplicationContext）
     * @return DecoderManager实例
     */
    public static DecoderManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DecoderManager.class) {
                if (instance == null) {
                    instance = new DecoderManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private DecoderManager(Context context) {
        mContext = context;
        // 从SharedPreferences初始化解码器模式
        initDecoderModeFromSP();
    }

    /**
     * 从SharedPreferences读取解码器模式
     */
    private void initDecoderModeFromSP() {
        SharedPreferences sp = mContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String modeStr = sp.getString(KEY_DECODER_MODE, "auto");
        mCurrentDecoderMode = parseDecoderMode(modeStr);
        Log.d(TAG, "从SP初始化解码器模式：" + getDecoderModeName(mCurrentDecoderMode));
    }

    /**
     * 解析字符串类型的解码器模式为int常量
     * @param modeStr 模式字符串（auto/hard/soft）
     * @return 解码器模式常量
     */
    public int parseDecoderMode(String modeStr) {
        if (modeStr == null) return DECODER_MODE_AUTO;
        switch (modeStr.toLowerCase(Locale.getDefault())) {
            case "hard":
                return DECODER_MODE_HARD;
            case "soft":
                return DECODER_MODE_SOFT;
            case "auto":
            default:
                return DECODER_MODE_AUTO;
        }
    }

    /**
     * 获取解码器模式名称（用于日志/UI展示）
     * @param mode 解码器模式常量
     * @return 模式名称
     */
    public String getDecoderModeName(int mode) {
        switch (mode) {
            case DECODER_MODE_HARD:
                return "硬解（强制）";
            case DECODER_MODE_SOFT:
                return "软解（优先）";
            case DECODER_MODE_AUTO:
            default:
                return "自动（硬解优先）";
        }
    }

    /**
     * 设置解码器模式
     * @param mode 解码器模式常量
     */
    public void setDecoderMode(int mode) {
        if (mCurrentDecoderMode == mode) return;

        int oldMode = mCurrentDecoderMode;
        mCurrentDecoderMode = mode;

        // 保存到SharedPreferences
        SharedPreferences sp = mContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String modeStr = getDecoderModeString(mode);
        sp.edit().putString(KEY_DECODER_MODE, modeStr).apply();

        Log.d(TAG, "解码器模式切换：" + getDecoderModeName(oldMode) + " → " + getDecoderModeName(mode));
        SettingsActivity.logOperation("【解码器】切换模式：" + getDecoderModeName(mode));

        // 通知监听器模式变化
        if (mModeChangeListener != null) {
            mModeChangeListener.onDecoderModeChanged(oldMode, mode);
        }
    }

    /**
     * 将解码器模式常量转为字符串（用于SP存储）
     * @param mode 解码器模式常量
     * @return 模式字符串
     */
    private String getDecoderModeString(int mode) {
        switch (mode) {
            case DECODER_MODE_HARD:
                return "hard";
            case DECODER_MODE_SOFT:
                return "soft";
            case DECODER_MODE_AUTO:
            default:
                return "auto";
        }
    }

    /**
     * 获取当前解码器模式
     * @return 解码器模式常量
     */
    public int getCurrentDecoderMode() {
        return mCurrentDecoderMode;
    }

    /**
     * 创建解码器选择器
     * @return MediaCodecSelector实例
     */
    public MediaCodecSelector createMediaCodecSelector() {
        return new SoftwareFirstMediaCodecSelector(mCurrentDecoderMode);
    }

    /**
     * 检测系统中可用的H.264解码器
     * 统计软解/硬解解码器数量，并输出日志
     */
    public void detectSystemH264Decoders() {
        try {
            List<MediaCodecInfo> h264Codecs = MediaCodecUtil.getDecoderInfos(
                    "video/avc", false, false);
            int softCount = 0;
            int hardCount = 0;
            StringBuilder softNames = new StringBuilder();
            StringBuilder hardNames = new StringBuilder();

            for (MediaCodecInfo codec : h264Codecs) {
                String name = codec.name;
                if (isSoftwareDecoder(name)) {
                    softCount++;
                    if (softCount <= 3) {
                        if (softCount > 1) softNames.append(", ");
                        softNames.append(name);
                    }
                } else {
                    hardCount++;
                    if (hardCount <= 3) {
                        if (hardCount > 1) hardNames.append(", ");
                        hardNames.append(name);
                    }
                }
            }

            Log.d(TAG, "【解码器】H.264 解码器统计：软解 " + softCount + " 个，硬解 " + hardCount + " 个");
            Log.d(TAG, "【解码器】软解解码器：" + softNames.toString());
            Log.d(TAG, "【解码器】硬解解码器：" + hardNames.toString());
            SettingsActivity.logOperation("【解码器】系统解码器：软解 " + softCount + " 个，硬解 " + hardCount + " 个");

            if (softCount == 0) {
                Log.w(TAG, "【解码器】⚠️ 系统未找到软件解码器，软解模式可能不生效");
                SettingsActivity.logOperation("【解码器】⚠️ 警告：未找到系统软件解码器");
            }
        } catch (Exception e) {
            Log.e(TAG, "【解码器】检测系统解码器失败：" + e.getMessage());
        }
    }

    /**
     * 判断解码器是否为软件解码器
     * @param codecName 解码器名称
     * @return true=软件解码器，false=硬件解码器
     */
    public static boolean isSoftwareDecoder(String codecName) {
        if (codecName == null) return false;
        String lowerName = codecName.toLowerCase(Locale.getDefault());
        return lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.");
    }

    /**
     * 注册解码器模式变化广播接收器
     */
    public void registerDecoderModeReceiver() {
        if (mIsReceiverRegistered) return;

        try {
            mBroadcastReceiver = new DecoderModeBroadcastReceiver();
            IntentFilter filter = new IntentFilter(ACTION_DECODER_MODE_CHANGED);
            mContext.registerReceiver(mBroadcastReceiver, filter);
            mIsReceiverRegistered = true;
            Log.d(TAG, "解码器模式广播接收器已注册");
            SettingsActivity.logOperation("【解码器】广播接收器已注册");
        } catch (Exception e) {
            Log.e(TAG, "注册解码器广播接收器失败：" + e.getMessage());
            SettingsActivity.logOperation("【解码器】广播注册失败：" + e.getMessage());
        }
    }

    /**
     * 注销解码器模式变化广播接收器
     */
    public void unregisterDecoderModeReceiver() {
        if (!mIsReceiverRegistered) return;

        try {
            if (mBroadcastReceiver != null) {
                mContext.unregisterReceiver(mBroadcastReceiver);
                mBroadcastReceiver = null;
            }
            mIsReceiverRegistered = false;
            Log.d(TAG, "解码器模式广播接收器已注销");
            SettingsActivity.logOperation("【解码器】广播接收器已注销");
        } catch (Exception e) {
            Log.e(TAG, "注销解码器广播接收器失败：" + e.getMessage());
        }
    }

    /**
     * 设置解码器模式变化监听器
     * @param listener 监听器实例
     */
    public void setOnDecoderModeChangeListener(OnDecoderModeChangeListener listener) {
        mModeChangeListener = listener;
    }

    /**
     * 解码器模式变化监听器接口
     */
    public interface OnDecoderModeChangeListener {
        /**
         * 解码器模式变化回调
         * @param oldMode 旧模式
         * @param newMode 新模式
         */
        void onDecoderModeChanged(int oldMode, int newMode);
    }

    /**
     * 解码器模式广播接收器
     */
    private class DecoderModeBroadcastReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_DECODER_MODE_CHANGED.equals(intent.getAction())) {
                // 重新从SP读取模式并应用
                initDecoderModeFromSP();
                setDecoderMode(mCurrentDecoderMode);

                String modeName = getDecoderModeName(mCurrentDecoderMode);
                SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
                Log.d(TAG, "收到解码器模式变化广播，当前模式：" + modeName);
            }
        }
    }

    /**
     * 解码器选择器实现类
     * 根据指定模式筛选解码器（软解优先/硬解强制/自动）
     */
    public static class SoftwareFirstMediaCodecSelector implements MediaCodecSelector {
        private final int decoderMode;

        public SoftwareFirstMediaCodecSelector(int mode) {
            this.decoderMode = mode;
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                String mimeType,
                boolean requiresSecureDecoder,
                boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {

            List<MediaCodecInfo> allCodecs = MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

            if (allCodecs == null || allCodecs.isEmpty()) {
                return allCodecs;
            }

            switch (decoderMode) {
                case DECODER_MODE_HARD:
                    // 只保留硬解码器
                    List<MediaCodecInfo> hardCodecs = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (!isSoftwareDecoder(codec.name)) {
                            hardCodecs.add(codec);
                        }
                    }
                    return hardCodecs;

                case DECODER_MODE_SOFT:
                    // 软解码器优先，后加硬解码器
                    List<MediaCodecInfo> softCodecs = new ArrayList<>();
                    List<MediaCodecInfo> hardCodecs2 = new ArrayList<>();
                    for (MediaCodecInfo codec : allCodecs) {
                        if (isSoftwareDecoder(codec.name)) {
                            softCodecs.add(codec);
                        } else {
                            hardCodecs2.add(codec);
                        }
                    }
                    List<MediaCodecInfo> result = new ArrayList<>();
                    result.addAll(softCodecs);
                    result.addAll(hardCodecs2);
                    return result;

                case DECODER_MODE_AUTO:
                default:
                    // 自动模式，返回所有解码器（系统默认顺序）
                    return allCodecs;
            }
        }
    }
}
