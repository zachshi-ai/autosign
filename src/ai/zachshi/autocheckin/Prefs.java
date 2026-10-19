package ai.zachshi.autocheckin;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 简单配置与运行日志存储。 */
public class Prefs {
    private static final String FILE = "autocheckin";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_HOUR = "hour";
    public static final String KEY_MINUTE = "minute";
    public static final String KEY_PKG = "target_pkg";
    public static final String KEY_LOG = "run_log";
    public static final String KEY_LAST_RESULT = "last_result";

    public static final String DEFAULT_PKG = "com.stepos.ailauncher";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static int hour(Context c) {
        return sp(c).getInt(KEY_HOUR, 8);
    }

    public static int minute(Context c) {
        return sp(c).getInt(KEY_MINUTE, 30);
    }

    public static void setTime(Context c, int h, int m) {
        sp(c).edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).apply();
    }

    public static String pkg(Context c) {
        String v = sp(c).getString(KEY_PKG, DEFAULT_PKG);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_PKG : v.trim();
    }

    public static void setPkg(Context c, String pkg) {
        sp(c).edit().putString(KEY_PKG, pkg.trim()).apply();
    }

    public static String log(Context c) {
        return sp(c).getString(KEY_LOG, "");
    }

    public static void clearLog(Context c) {
        sp(c).edit().putString(KEY_LOG, "").apply();
    }

    public static void appendLog(Context c, String line) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        String old = sp(c).getString(KEY_LOG, "");
        String merged = old + ts + "  " + line + "\n";
        if (merged.length() > 20000) {
            merged = merged.substring(merged.length() - 20000);
        }
        sp(c).edit().putString(KEY_LOG, merged).apply();
    }

    public static void setLastResult(Context c, String v) {
        sp(c).edit().putString(KEY_LAST_RESULT, v).apply();
    }

    public static String lastResult(Context c) {
        return sp(c).getString(KEY_LAST_RESULT, "尚未执行");
    }
}
