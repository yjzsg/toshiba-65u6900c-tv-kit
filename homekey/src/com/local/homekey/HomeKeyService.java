package com.local.homekey;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Home 键 + 黑屏看门狗。
 *
 * 背景一（Home 键没反应）：
 * 这台 TCL 的 Home 键被 PhoneWindowManager 硬编码成启动 com.tcl.cyberui/.MainActivity，
 * 而那个组件在本机是坏的，按下去毫无反应。
 *
 * 背景二（退出 App 后黑屏）：
 * 系统里已经没有可用的 HOME 提供者 —— cyberui 坏了，Aerial 又没法声明 CATEGORY_HOME
 * （TCL 安装门禁会拒，实测 INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES）。
 * 于是任何 App 退出后系统"无家可归"，焦点变 null，只有一层 KeyguardScrim 露出来 —— 全屏黑。
 *
 * 做法：
 *   1. Home 键：接 CLOSE_SYSTEM_DIALOGS(reason=homekey) 广播 / 截 KEYCODE_HOME，拉起 Aerial；
 *   2. 黑屏看门狗：用 UsageStatsManager 观察前台切换。如果某个 App 退到后台之后
 *      4 秒内没有任何新的前台出现，说明系统卡住了，直接把 Aerial 拉回来。
 *
 * 注意：不能用 getWindows() 判断 —— 实测这台 TCL 的 WindowManagerService
 * 没实现 getWindowsForAccessibility，服务虽然拿到了读窗口内容的权限，
 * getWindows() 仍然返回空列表。
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKey";

    /** 轮询间隔。 */
    private static final long TICK_MS = 1500;

    /** 两次拉起之间的冷却。 */
    private static final long COOLDOWN_MS = 8000;

    /** 退到后台后多久没人接盘就判定为卡死。 */
    private static final long STUCK_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long lastQueryAt = 0;
    private String curFgPkg = null;
    private long curFgAt = 0;
    private long lastBgAt = 0;
    private String lastBgPkg = null;

    private long lastActionAt = 0;
    private boolean firstTickLogged = false;

    /** 开机兜底最多尝试几次。 */
    private static final int BOOT_TRIES_MAX = 8;

    private int bootTries = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        try {
            IntentFilter f = new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS");
            registerReceiver(homeBroadcast, f);
        } catch (Exception e) {
            Log.w(TAG, "could not watch CLOSE_SYSTEM_DIALOGS", e);
        }

        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, TICK_MS);

        // 开机兜底：本机没有 HOME，TCL 的开机流程会一直等"第一个 Activity 显示出来"，
        // sys.boot_completed 永远不置位，卡在开机动画的 loading_ui。
        // 无障碍服务在 boot_completed 之前就已经被绑定了，这里主动拉一次 Aerial 打破僵局。
        bootTries = 0;
        handler.removeCallbacks(bootBootstrap);
        handler.postDelayed(bootBootstrap, 3000);

        Log.i(TAG, "service connected; key filter + usage watchdog + boot bootstrap armed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 看门狗用轮询。
    }

    private final BroadcastReceiver homeBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.i(TAG, "CLOSE_SYSTEM_DIALOGS reason=" + reason);
            if ("homekey".equals(reason)) {
                Log.i(TAG, "homekey broadcast; bringing Aerial to front");
                bringAerial("homekey-broadcast");
            }
        }
    };

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Home key taken; bringing Aerial to front");
            bringAerial("home-key");
        }
        return true;
    }

    // ---------------------------------------------------------------- 看门狗

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog tick failed", t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------- 开机兜底（无 HOME）

    private final Runnable bootBootstrap = new Runnable() {
        @Override
        public void run() {
            String bc = readProp("sys.boot_completed");
            if ("1".equals(bc)) {
                Log.i(TAG, "boot already complete; bootstrap idle");
                return;
            }
            bootTries++;
            Log.i(TAG, "boot not complete (bc='" + bc + "'); pulling Aerial up ("
                    + bootTries + "/" + BOOT_TRIES_MAX + ")");
            bringAerial("boot-bootstrap");
            if (bootTries < BOOT_TRIES_MAX) {
                handler.postDelayed(this, 5000);
            }
        }
    };

    /** 读系统属性（只读，不需要权限）。 */
    private String readProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void tick() {
        if (!isScreenOn()) {
            lastQueryAt = 0;
            return;
        }

        UsageStatsManager usm =
                (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;

        long now = System.currentTimeMillis();
        if (lastQueryAt == 0) lastQueryAt = now - 5000;

        UsageEvents events = usm.queryEvents(lastQueryAt, now);
        lastQueryAt = now;

        if (events != null) {
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (!e.getPackageName().equals(curFgPkg)) {
                        Log.i(TAG, "fg -> " + e.getPackageName());
                    }
                    curFgPkg = e.getPackageName();
                    curFgAt = e.getTimeStamp();
                } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    Log.i(TAG, "bg <- " + e.getPackageName());
                    lastBgPkg = e.getPackageName();
                    lastBgAt = e.getTimeStamp();
                }
            }
        }

        if (!firstTickLogged) {
            firstTickLogged = true;
            Log.i(TAG, "watchdog first tick: fg=" + curFgPkg + ", bgAt=" + lastBgAt);
        }

        // 从来没看到过前台事件，说明没权限或还没数据，不乱动。
        if (curFgPkg == null) return;

        boolean stuck = lastBgAt > curFgAt && (now - lastBgAt) > STUCK_MS;
        if (!stuck) return;

        if (now - lastActionAt < COOLDOWN_MS) return;
        lastActionAt = now;

        Log.i(TAG, "watchdog: " + lastBgPkg + " went background "
                + (now - lastBgAt) + "ms ago and nothing took over; pulling Aerial back");
        bringAerial("watchdog");
    }

    private boolean isScreenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- 动作

    private void bringAerial(String from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setComponent(new ComponentName("app.orchard.tv", "app.orchard.tv.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("bringBack", true);
            startActivity(i);
            Log.i(TAG, "Aerial started (from=" + from + ")");
        } catch (Exception e) {
            Log.w(TAG, "could not start Aerial", e);
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(watchdog);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        try {
            unregisterReceiver(homeBroadcast);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
