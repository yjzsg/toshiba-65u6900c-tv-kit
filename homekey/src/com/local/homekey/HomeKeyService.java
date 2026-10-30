package com.local.homekey;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 专门抢 Home 键的无障碍服务。
 *
 * 背景：这台 TCL 的 Home 键被 PhoneWindowManager 硬编码成启动
 * com.tcl.cyberui/.MainActivity，而这个 Activity 在本机是坏的（缺类，
 * 启动即崩），于是 Home 键按下去毫无反应。
 *
 * Aerial 的 HomeGuardService 本该用无障碍按键过滤来接管 Home，但它在
 * Android 6 上会因为别的输入设备被误判成手柄（sim-mouse 的 sources
 * 0x2703 含 SOURCE_GAMEPAD）而主动关掉过滤，所以轮不到它。
 *
 * 这个服务只做一件事：在 onKeyEvent 里截下 KEYCODE_HOME，按下即把
 * Aerial 拉回前台，不让事件继续传给系统（系统会去找坏掉的 cyberui）。
 * 它不声明 FLAG_DEFAULT_HOME，因此在 Android 6 的按键分发里排在
 * Aerial 之前，能第一个拿到 Home 键。
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "HomeKey";

    /** Aerial 的前台 Activity。 */
    private static final String AERIAL = "app.orchard.tv/.MainActivity";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        // 必须运行时显式打开，否则收不到按键。
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        // 备用路径：系统处理 Home 键时会广播 CLOSE_SYSTEM_DIALOGS，reason=homekey。
        try {
            IntentFilter f = new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS");
            registerReceiver(homeBroadcast, f);
        } catch (Exception e) {
            Log.w(TAG, "could not watch CLOSE_SYSTEM_DIALOGS", e);
        }
        Log.i(TAG, "service connected, filtering keys on");
    }

    private final BroadcastReceiver homeBroadcast = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.i(TAG, "CLOSE_SYSTEM_DIALOGS reason=" + reason);
            if ("homekey".equals(reason)) {
                Log.i(TAG, "homekey broadcast; bringing Aerial to front");
                bringAerial();
            }
        }
    };

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "Home taken; bringing Aerial to front");
            bringAerial();
        }
        return true;
    }

    private void bringAerial() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setComponent(new ComponentName("app.orchard.tv", "app.orchard.tv.MainActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("bringBack", true);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start Aerial", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
