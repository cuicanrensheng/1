package com.iptvlive.util;

import com.google.android.exoplayer2.PlaybackException;

/**
 * 播放错误分类：网络/格式/解码/其他
 */
public class PlayErrClassUtil {
    public static String getErrTypeName(PlaybackException err) {
        switch (err.errorCode) {
            //网络错误
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
                return "【网络异常】";
            //媒体格式错误（删除不存在的 ERROR_CODE_PARSING_FORMAT_UNSUPPORTED）
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
                return "【格式异常】";
            //解码错误
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "【解码异常】";
            default:
                return "【其他异常】";
        }
    }
}
