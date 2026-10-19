package ai.zachshi.autocheckin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView statusView;
    private TextView logView;
    private EditText pkgEdit;
    private Switch enableSwitch;
    private Button timeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        int pad = dp(20);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("自动签到");
        title.setTextSize(24);
        title.setGravity(Gravity.START);
        content.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(8), 0, dp(8));
        content.addView(statusView);

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView switchLabel = new TextView(this);
        switchLabel.setText("每日自动签到");
        switchLabel.setTextSize(16);
        switchLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        enableSwitch = new Switch(this);
        switchRow.addView(switchLabel);
        switchRow.addView(enableSwitch);
        content.addView(switchRow);

        timeButton = new Button(this);
        content.addView(timeButton);

        content.addView(button("开启无障碍服务", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请在列表中找到『自动签到』并打开", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));

        content.addView(button("立即执行一次", v -> {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                return;
            }
            sendBroadcast(new Intent(AutoSignService.ACTION_RUN).setPackage(getPackageName()));
            Toast.makeText(this, "已触发，稍候查看日志", Toast.LENGTH_SHORT).show();
            logView.postDelayed(this::refreshLog, 1500);
        }));

        content.addView(button("扫描当前界面（用于校准）", v -> {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                return;
            }
            sendBroadcast(new Intent(AutoSignService.ACTION_SCAN).setPackage(getPackageName()));
            logView.postDelayed(this::refreshLog, 1200);
        }));

        content.addView(button("忽略电池优化（防止被杀）", v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        }));

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("对话应用包名（默认 Amoo）");
        pkgLabel.setTextSize(13);
        pkgLabel.setPadding(0, dp(16), 0, dp(4));
        content.addView(pkgLabel);

        pkgEdit = new EditText(this);
        pkgEdit.setSingleLine(true);
        pkgEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        content.addView(pkgEdit);

        content.addView(button("保存包名", v -> {
            Prefs.setPkg(this, pkgEdit.getText().toString());
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            refresh();
        }));

        content.addView(button("清空日志", v -> {
            Prefs.clearLog(this);
            refreshLog();
        }));

        TextView logLabel = new TextView(this);
        logLabel.setText("运行日志");
        logLabel.setTextSize(15);
        logLabel.setPadding(0, dp(16), 0, dp(6));
        content.addView(logLabel);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        content.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        enableSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setEnabled(this, checked);
            if (checked) {
                Scheduler.schedule(this);
            } else {
                Scheduler.cancel(this);
                Prefs.appendLog(this, "已关闭每日自动签到");
            }
            refresh();
            refreshLog();
        });

        timeButton.setOnClickListener(v -> {
            TimePickerDialog d = new TimePickerDialog(this,
                    (view, h, m) -> {
                        Prefs.setTime(this, h, m);
                        if (Prefs.enabled(this)) Scheduler.schedule(this);
                        refresh();
                        refreshLog();
                    }, Prefs.hour(this), Prefs.minute(this), true);
            d.show();
        });
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void refresh() {
        boolean acc = isAccessibilityEnabled();
        boolean on = Prefs.enabled(this);
        String next = on ? Scheduler.describeNext(Prefs.hour(this), Prefs.minute(this)) : "—";
        String last = Prefs.lastResult(this);
        String now = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());

        statusView.setText(
                (acc ? "✅ 无障碍服务已开启" : "⚠️ 无障碍服务未开启（必须开启才能自动点击）")
                        + "\n" + (on ? "✅ 每日自动签到已启用，下次：" + next : "⏸ 每日自动签到未启用")
                        + "\n最近结果：" + last
                        + "\n当前时间：" + now);

        enableSwitch.setOnCheckedChangeListener(null);
        enableSwitch.setChecked(on);
        enableSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setEnabled(this, checked);
            if (checked) Scheduler.schedule(this);
            else {
                Scheduler.cancel(this);
                Prefs.appendLog(this, "已关闭每日自动签到");
            }
            refresh();
            refreshLog();
        });
        timeButton.setText(String.format(Locale.CHINA, "签到时间：%02d:%02d", Prefs.hour(this), Prefs.minute(this)));
        if (!pkgEdit.hasFocus()) pkgEdit.setText(Prefs.pkg(this));
        refreshLog();
    }

    private void refreshLog() {
        String log = Prefs.log(this);
        logView.setText(log.isEmpty() ? "（暂无日志）" : log);
    }

    private boolean isAccessibilityEnabled() {
        if (AutoSignService.isConnected()) return true;
        String expected = new ComponentName(this, AutoSignService.class).flattenToString();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
