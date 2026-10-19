package ai.zachshi.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后恢复定时任务。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Prefs.enabled(ctx)) {
            Prefs.appendLog(ctx, "设备启动，恢复定时任务");
            Scheduler.schedule(ctx);
        }
    }
}
