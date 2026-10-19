package ai.zachshi.autocheckin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 无障碍服务：按「对话界面 → 左上角菜单 → 右上角设置 → 我的任务 → 签到」的顺序自动操作。
 * 每一步都优先按控件文字 / 描述 / viewId 定位，找不到时按屏幕相对坐标兜底，并校验上一步是否生效。
 * v1.1：新增界面结构导出（Download/autosign_trace.txt），便于离线校准。
 */
public class AutoSignService extends AccessibilityService {

    public static final String ACTION_RUN = "ai.zachshi.autocheckin.RUN";
    public static final String ACTION_SCAN = "ai.zachshi.autocheckin.SCAN";

    private static final String TAG = "AutoSign";
    private static final long MAX_RUN_MS = 120_000L;

    private static volatile boolean connected = false;
    private static AutoSignService instance;

    private Handler handler;
    private BroadcastReceiver receiver;

    private boolean running = false;
    private long runStart = 0;
    private int attempts = 0;
    private String targetPkg = Prefs.DEFAULT_PKG;
    private int taskClicks = 0;
    private final StringBuilder trace = new StringBuilder();

    private static final String[] MENU_KEYS = {"侧边栏", "菜单", "对话列表", "历史对话", "navigation drawer", "menu", "drawer"};
    private static final String[] MENU_IDS = {"menu", "drawer", "nav", "left", "back", "title"};
    private static final String[] SETTINGS_KEYS = {"设置", "設定", "Settings", "settings"};
    private static final String[] TASK_KEYS = {"我的任务", "我的任務", "任务中心", "任务"};
    private static final String[] CHECKIN_KEYS = {"签到", "簽到", "打卡", "立即领取"};
    private static final String[] DONE_KEYS = {"已签到", "已簽到", "已打卡", "今日已签", "签到成功", "已领取", "已完成", "明日再来"};
    private static final String[] DRAWER_HINTS = {"我的任务", "历史对话", "新对话", "新建对话", "清空对话", "会话列表", "设置"};

    public static boolean isConnected() {
        return connected && instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connected = true;
        handler = new Handler(Looper.getMainLooper());
        registerReceiver();
        Prefs.appendLog(this, "无障碍服务已连接（v1.5）");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        instance = null;
        try {
            if (receiver != null) unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiver = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 事件仅用于占位
    }

    @Override
    public void onInterrupt() {
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent == null ? null : intent.getAction();
                if (ACTION_RUN.equals(a)) {
                    startRun("触发");
                } else if (ACTION_SCAN.equals(a)) {
                    scanScreen();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_RUN);
        f.addAction(ACTION_SCAN);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
    }

    // ---------------- 流程 ----------------

    private void startRun(String from) {
        if (running) {
            log("已有任务在执行，忽略新的触发");
            return;
        }
        running = true;
        runStart = System.currentTimeMillis();
        attempts = 0;
        taskClicks = 0;
        trace.setLength(0);
        targetPkg = Prefs.pkg(this);
        Prefs.setLastResult(this, "执行中…");
        log("===== 开始签到（" + from + "，目标应用 " + targetPkg + "）=====");
        trace("初始界面").append(fullDump()).append("\n");
        handler.post(guard(this::stepLaunch));
    }

    private void stepLaunch() {
        attempts++;
        String cur = currentPackage();
        if (cur != null && cur.equals(targetPkg)) {
            log("已在对话应用：" + cur);
            attempts = 0;
            next(this::stepEnter, 900);
            return;
        }
        if (attempts == 1) {
            log("当前前台：" + cur + "，返回桌面进入对话应用");
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if (attempts == 2) {
            log("尝试直接启动 " + targetPkg);
            launchTarget();
        } else if (attempts >= 5) {
            fail("无法进入对话应用（当前前台：" + cur + "）");
            return;
        } else {
            log("仍不在对话应用，重试返回桌面");
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
        next(this::stepLaunch, 1600);
    }

    /** 长按屏幕底部，唤出 Amoo 对话界面 */
    private void stepEnter() {
        attempts++;
        if (inChatUi()) {
            log("已进入对话界面");
            attempts = 0;
            next(this::stepNav, 800);
            return;
        }
        switch (attempts) {
            case 1:
                log("长按屏幕底部，唤出对话界面");
                longPress(0.5f, 0.965f, 900);
                break;
            case 2:
                log("再次长按底部（略高位置）");
                longPress(0.5f, 0.93f, 900);
                break;
            case 3:
                log("尝试长按底部中间偏右");
                longPress(0.7f, 0.965f, 900);
                break;
            case 4:
                log("尝试长按底部快捷键区");
                longPress(0.5f, 0.88f, 1000);
                break;
            default:
                fail("长按底部未能唤出对话界面");
                return;
        }
        next(this::stepEnter, 1700);
    }

    /** 是否已经处在对话界面：有输入框，或左上角出现可点图标 */
    private boolean inChatUi() {
        Rect s = screenRect();
        boolean hasInput = false;
        boolean hasTopLeftIcon = false;
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser()) continue;
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            if (cls.contains("EditText")) {
                hasInput = true;
            }
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.centerY() < s.height() * 0.09f && r.left < s.width() * 0.2f
                    && r.width() < s.width() * 0.35f && clickableNode(n) != null) {
                hasTopLeftIcon = true;
            }
            if (hasInput || hasTopLeftIcon) return true;
        }
        return hasInput || hasTopLeftIcon;
    }

    private boolean longPress(float fx, float fy, long ms) {
        try {
            Rect s = screenRect();
            Path p = new Path();
            p.moveTo(s.width() * fx, s.height() * fy);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, ms);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            log("长按失败：" + e);
            return false;
        }
    }

    /** 页面识别 + 分页处理：面板点齿轮、对话页返回、设置页点我的任务、见到签到就点 */
    private void stepNav() {
        attempts++;
        if (attempts == 1) trace("任务入口前").append(fullDump()).append("\n");
        if (attempts > 14) {
            fail("未能进入任务页");
            return;
        }
        if (hasTextAny(DONE_KEYS)) {
            finish(true, "今日已签到，无需重复操作");
            return;
        }
        if (hasTextAny(CHECKIN_KEYS) && clickByKeywords(CHECKIN_KEYS, false)) {
            log("已点击签到按钮，等待结果…");
            attempts = 0;
            next(this::stepVerify, 1800);
            return;
        }
        int p = detectPage();
        if (p == PAGE_SETTINGS) {
            if (hasText("我的任务") && clickByKeywords(new String[]{"我的任务"}, false)) {
                log("设置页：已点击『我的任务』");
            } else {
                log("设置页：暂未看到『我的任务』，下滑查找");
                scrollForward();
            }
        } else if (p == PAGE_PANEL) {
            if (clickByViewId(new String[]{"setting", "gear", "ic_setting"})) {
                log("全部任务面板：按 id 命中设置按钮");
            } else {
                float[][] pts = {{0.718f, 0.147f}, {0.700f, 0.147f}, {0.735f, 0.147f}, {0.718f, 0.158f}};
                float[] pt = pts[(attempts - 1) % pts.length];
                log(String.format(Locale.CHINA, "全部任务面板：点击右上角齿轮 (%.3f, %.3f)", pt[0], pt[1]));
                tapRatio(pt[0], pt[1]);
            }
        } else if (p == PAGE_CHAT) {
            log("当前在对话页，返回上一层");
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else {
            log("未识别的页面，尝试打开『全部任务』");
            if (!clickByViewId(new String[]{"btn_all_tasks", "all_tasks"})) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }
        next(this::stepNav, 1300);
    }

    private static final int PAGE_UNKNOWN = 0, PAGE_PANEL = 1, PAGE_CHAT = 2, PAGE_SETTINGS = 3;

    private int detectPage() {
        if (hasText("我的任务")) return PAGE_SETTINGS;
        if (hasText("全部任务") || hasText("搜索历史会话") || hasText("Amoo Store")) return PAGE_PANEL;
        if (hasText("发消息或按住说话") || hasText("有什么能帮你的")) return PAGE_CHAT;
        if (hasText("设置")) return PAGE_SETTINGS;
        return PAGE_UNKNOWN;
    }

    private void stepCheckin() {
        attempts++;
        if (attempts == 1) trace("任务页").append(fullDump()).append("\n");
        if (hasTextAny(DONE_KEYS)) {
            finish(true, "今日已签到，无需重复操作");
            return;
        }
        if (clickByKeywords(CHECKIN_KEYS, false)) {
            log("已点击签到按钮，等待结果…");
            attempts = 0;
            next(this::stepVerify, 1800);
            return;
        }
        if (attempts >= 6) {
            fail("未找到签到按钮");
            return;
        }
        scrollForward();
        next(this::stepCheckin, 900);
    }

    private void stepVerify() {
        attempts++;
        if (hasTextAny(DONE_KEYS)) {
            finish(true, "签到成功");
            return;
        }
        if (attempts <= 4) {
            next(this::stepVerify, 1200);
            return;
        }
        finish(false, "已点击签到，但未识别到明确结果，请打开 App 核对");
    }

    private void next(Runnable r, long delay) {
        handler.postDelayed(guard(r), delay);
    }

    private Runnable guard(Runnable r) {
        return () -> {
            if (!running) return;
            if (System.currentTimeMillis() - runStart > MAX_RUN_MS) {
                fail("执行超时，已停止");
                return;
            }
            try {
                r.run();
            } catch (Exception e) {
                log("步骤异常：" + e);
                fail("执行出错：" + e.getMessage());
            }
        };
    }

    private void fail(String why) {
        finish(false, why);
    }

    private void finish(boolean ok, String msg) {
        running = false;
        long cost = (System.currentTimeMillis() - runStart) / 1000;
        String line = (ok ? "✅ " : "⚠️ ") + msg + "（耗时 " + cost + "s）";
        log(line);
        Prefs.setLastResult(this, line);
        trace("结束时界面").append(fullDump()).append("\n");
        String file = saveToDownload("autosign_trace.txt", buildTraceReport(msg, ok));
        if (file != null) log("界面结构已导出：" + file);
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    private String buildTraceReport(String msg, boolean ok) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        return "自动签到 v1.5 运行报告\n时间：" + ts + "\n结果：" + (ok ? "成功" : "未完成") + " — " + msg
                + "\n目标应用：" + targetPkg + "\n\n" + trace;
    }

    // ---------------- 界面读取 ----------------

    private StringBuilder trace(String title) {
        return trace.append("\n---- ").append(title).append(" ----\n");
    }

    private String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return root.getPackageName().toString();
    }

    private void launchTarget() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                log("该应用没有启动入口（可能是系统桌面），已改为回到桌面");
            }
        } catch (Exception e) {
            log("启动目标应用失败：" + e);
        }
    }

    private List<AccessibilityNodeInfo> allNodes() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) collect(root, out, 0);
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 2500) return;
        out.add(n);
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) collect(c, out, depth + 1);
        }
    }

    private static String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String s = (t == null ? "" : t.toString());
        String sd = (d == null ? "" : d.toString());
        if (s.isEmpty()) return sd;
        if (sd.isEmpty()) return s;
        return s + " " + sd;
    }

    private boolean hasText(String key) {
        for (AccessibilityNodeInfo n : allNodes()) {
            if (n.isVisibleToUser() && textOf(n).contains(key)) return true;
        }
        return false;
    }

    private boolean hasTextAny(String[] keys) {
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser()) continue;
            String s = textOf(n);
            if (s.isEmpty()) continue;
            for (String k : keys) {
                if (s.contains(k)) return true;
            }
        }
        return false;
    }

    /** 侧边栏打开：出现抽屉关键字，或存在一个不满屏的窗口 */
    private boolean drawerOpen() {
        for (String k : DRAWER_HINTS) {
            if (hasText(k)) return true;
        }
        try {
            Rect s = screenRect();
            for (AccessibilityWindowInfo w : getWindows()) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (r.width() > 0 && r.width() < s.width() * 0.92f && r.height() > s.height() * 0.4f) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean clickByKeywords(String[] keys, boolean preferTop) {
        List<AccessibilityNodeInfo> nodes = allNodes();
        Rect screen = screenRect();
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String s = textOf(n);
            if (s.isEmpty()) continue;
            boolean matched = false;
            for (String k : keys) {
                if (s.contains(k)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            int score = 0;
            if (preferTop) score += (int) (1000 - (r.centerY() * 1000L / Math.max(1, screen.height())));
            if (s.length() <= 12) score += 200;
            if (n.isClickable()) score += 300;
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best != null && clickNode(best);
    }

    private boolean clickByViewId(String[] ids) {
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser()) continue;
            String id = null;
            try {
                id = n.getViewIdResourceName();
            } catch (Exception ignored) {
            }
            if (id == null) continue;
            String low = id.toLowerCase(Locale.ROOT);
            for (String k : ids) {
                if (low.contains(k) && clickNode(n)) return true;
            }
        }
        return false;
    }

    private boolean clickTopLeftIcon() {
        Rect screen = screenRect();
        int w = screen.width();
        int h = screen.height();
        AccessibilityNodeInfo best = null;
        int bestLeft = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.left > w * 0.25f || r.centerY() > h * 0.12f) continue;
            if (r.width() > w * 0.5f) continue;
            if (clickableNode(n) == null) continue;
            if (r.left < bestLeft) {
                bestLeft = r.left;
                best = n;
            }
        }
        return best != null && clickNode(best);
    }

    private boolean clickSecondFromRightTopIcon() {
        Rect screen = screenRect();
        int w = screen.width();
        int h = screen.height();
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo n : allNodes()) {
            if (!n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            if (r.centerY() > h * 0.12f) continue;
            if (r.left < w * 0.55f) continue;
            if (r.width() > w * 0.4f) continue;
            if (clickableNode(n) == null) continue;
            candidates.add(n);
        }
        Collections.sort(candidates, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect();
                Rect rb = new Rect();
                a.getBoundsInScreen(ra);
                b.getBoundsInScreen(rb);
                return Integer.compare(rb.left, ra.left);
            }
        });
        if (candidates.isEmpty()) return false;
        AccessibilityNodeInfo target = candidates.size() >= 2 ? candidates.get(1) : candidates.get(0);
        return clickNode(target);
    }

    private AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            AccessibilityNodeInfo p = cur.getParent();
            cur = p;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = clickableNode(n);
        if (t != null) {
            if (t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) return tap(r.centerX(), r.centerY());
        return false;
    }

    private boolean tapRatio(float fx, float fy) {
        Rect s = screenRect();
        return tap((int) (s.width() * fx), (int) (s.height() * fy));
    }

    private boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 70);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            log("点击失败：" + e);
            return false;
        }
    }

    private boolean swipeFromLeftEdge() {
        try {
            Rect s = screenRect();
            int y = (int) (s.height() * 0.45f);
            Path p = new Path();
            p.moveTo(2, y);
            p.lineTo((int) (s.width() * 0.75f), y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 320);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            log("从屏幕左缘右滑，尝试拉出侧边栏");
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            log("滑动失败：" + e);
            return false;
        }
    }

    private void scrollForward() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            AccessibilityNodeInfo r = findScrollable(c);
            if (r != null) return r;
        }
        return null;
    }

    private Rect screenRect() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    /** 完整界面结构，用于离线校准 */
    private String fullDump() {
        StringBuilder sb = new StringBuilder();
        Rect s = screenRect();
        sb.append("screen=").append(s.width()).append("x").append(s.height()).append("\n");
        sb.append("pkg=").append(currentPackage()).append("\n");
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                sb.append("window type=").append(w.getType()).append(" bounds=")
                        .append(r.left).append(",").append(r.top).append("-")
                        .append(r.right).append(",").append(r.bottom).append("\n");
            }
        } catch (Exception ignored) {
        }
        List<AccessibilityNodeInfo> nodes = allNodes();
        sb.append("nodeCount=").append(nodes.size()).append("\n");
        int printed = 0;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
            int dot = cls.lastIndexOf('.');
            if (dot > 0) cls = cls.substring(dot + 1);
            String t = n.getText() == null ? "" : n.getText().toString().replace("\n", " ");
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().replace("\n", " ");
            String id = "";
            try {
                id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
            } catch (Exception ignored) {
            }
            if (t.isEmpty() && d.isEmpty() && id.isEmpty() && !n.isClickable()) continue;
            sb.append(cls)
                    .append(" vis=").append(n.isVisibleToUser() ? 1 : 0)
                    .append(" click=").append(n.isClickable() ? 1 : 0)
                    .append(" scroll=").append(n.isScrollable() ? 1 : 0)
                    .append(" b=").append(r.left).append(",").append(r.top).append(",")
                    .append(r.right).append(",").append(r.bottom)
                    .append(" text=").append(t)
                    .append(" desc=").append(d)
                    .append(" id=").append(id)
                    .append("\n");
            if (++printed >= 220) {
                sb.append("... (truncated)\n");
                break;
            }
        }
        return sb.toString();
    }

    private void scanScreen() {
        String dump = fullDump();
        String file = saveToDownload("autosign_scan.txt",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "\n" + dump);
        for (String line : dump.split("\n")) {
            Prefs.appendLog(this, line);
        }
        Log.i(TAG, dump);
        if (file != null) {
            String tip = "已导出界面结构：" + file;
            Prefs.appendLog(this, tip);
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "扫描完成，但导出文件失败", Toast.LENGTH_LONG).show();
        }
    }

    /** 写入手机 Download 目录，返回可读路径 */
    private String saveToDownload(String name, String content) {
        byte[] data = content.getBytes();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return null;
                os.write(data);
                os.close();
                return "/sdcard/Download/" + name;
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore 写入失败", e);
        }
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.w(TAG, "外部私有目录写入失败", e);
        }
        return null;
    }

    private void log(String s) {
        Log.i(TAG, s);
        Prefs.appendLog(this, s);
    }
}
