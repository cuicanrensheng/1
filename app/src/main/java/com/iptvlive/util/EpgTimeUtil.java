package com.iptvlive.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * EPG时间工具：获取当日 yyyy-MM-dd
 */
public class EpgTimeUtil {
    public static String getNowYMD() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        return sdf.format(new Date());
    }
}
