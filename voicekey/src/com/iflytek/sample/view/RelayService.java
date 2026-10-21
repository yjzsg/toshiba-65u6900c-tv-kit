package com.iflytek.sample.view;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Transparent proxy for xiri's voice-view channel.
 *
 * xiri binds "com.iflytek.xiri.voice.VIEW" implicitly as soon as this package is
 * installed (tv.yuyin.view.MainView:315-330). Every UI update - including the
 * recognised string in SHOWUSERTEXT(10005) / SHOWUSERTEXTFLOW(10020) - arrives
 * here first, is remembered, and is then forwarded verbatim to com.tcl.walleve so
 * the stock assistant overlay keeps working exactly as before.
 */
public class RelayService extends Service {

    static final String TAG = "RELAY";
    static final String DESCRIPTOR = "tv.yuyin.view.aidl.IVoiceViewAidlInterface";
    static final int ACTION_SHOW_USER_TEXT = 10005;
    static final int ACTION_SHOW_USER_TEXT_FLOW = 10020;

    /** Latest transcript seen on the channel; polled by the IME. */
    static volatile String lastText;
    static volatile long lastTextAt;

    private volatile IBinder target;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            target = service;
            Log.d(TAG, "walleve bound");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            target = null;
        }
    };

    static void reset() {
        lastText = null;
        lastTextAt = 0L;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Intent i = new Intent("com.iflytek.xiri.voice.VIEW");
            i.setPackage("com.tcl.walleve");
            bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind walleve failed", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind " + (intent == null ? "null" : intent.getAction()));
        return new Relay();
    }

    @Override
    public void onDestroy() {
        try {
            unbindService(conn);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    class Relay extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == 1) {
                sniff(data);
            }
            IBinder t = target;
            if (t != null) {
                try {
                    return t.transact(code, data, reply, flags);
                } catch (Throwable e) {
                    Log.w(TAG, "forward " + code + " failed", e);
                }
            }
            reply.writeNoException();
            if (code == 1) {
                reply.writeInt(1);
                new Bundle().writeToParcel(reply, 0);
            }
            return true;
        }
    }

    private void sniff(Parcel data) {
        int start = data.dataPosition();
        try {
            data.enforceInterface(DESCRIPTOR);
            Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
            if (b != null) {
                int action = b.getInt("action", -1);
                if (action == ACTION_SHOW_USER_TEXT || action == ACTION_SHOW_USER_TEXT_FLOW) {
                    String obj = b.getString("obj");
                    if (obj != null && obj.length() > 0) {
                        lastText = obj;
                        lastTextAt = System.currentTimeMillis();
                        Log.d(TAG, "text[" + action + "] " + obj);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "sniff failed", t);
        } finally {
            data.setDataPosition(start);
        }
    }
}
