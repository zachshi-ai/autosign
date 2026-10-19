package ai.zachshi.autocheckin;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** 每日闹钟触发：唤醒屏幕并请求无障碍服务执行签到。 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Prefs.appendLog(ctx, "⏰ 定时任务触发");

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "autocheckin:alarm");
            wl.setReferenceCounted(false);
            try {
                wl.acquire(120000L);
            } catch (Exception ignored) {
            }
        }

        if (!Prefs.enabled(ctx)) {
            Prefs.appendLog(ctx, "自动签到已关闭，本次跳过");
        } else {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                Prefs.appendLog(ctx, "设备处于锁屏状态，无法自动操作，本次跳过；请关闭锁屏密码或保持解锁后使用");
                Prefs.setLastResult(ctx, "失败：设备锁屏");
            } else if (!AutoSignService.isConnected()) {
                Prefs.appendLog(ctx, "无障碍服务未开启，无法执行；请先在 App 内开启无障碍服务");
                Prefs.setLastResult(ctx, "失败：无障碍服务未开启");
            } else {
                Intent run = new Intent(AutoSignService.ACTION_RUN).setPackage(ctx.getPackageName());
                ctx.sendBroadcast(run);
            }
        }

        // 排定下一次
        if (Prefs.enabled(ctx)) {
            Scheduler.schedule(ctx);
        }
        if (wl != null && wl.isHeld()) wl.release();
    }
}
