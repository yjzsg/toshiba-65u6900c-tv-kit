package com.iflytek.sample.view;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client for xiri's push-audio recognition service.
 *
 * tv.yuyin.tcl.aidl.WriteRecognizerService is exported without any permission
 * guard, so any app may stream 16 kHz mono PCM16 into the very engine the voice
 * assistant uses. Transaction 1 starts a session, 2 pushes audio, 3 commits.
 */
final class XiriAsr {

    static final String TAG = "RELAY";
    static final String PKG = "com.iflytek.xiri";
    static final String SVC = "tv.yuyin.tcl.aidl.WriteRecognizerService";
    static final String DESC = "tv.yuyin.tcl.aidl.IWriteRecognizerAidlInterface";

    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile IBinder binder;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    boolean connect(Context ctx, long timeoutMs) {
        Intent i = new Intent();
        i.setClassName(PKG, SVC);
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind recognizer failed", t);
            return false;
        }
        if (!bound) {
            return false;
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return binder != null;
    }

    void start() {
        tx(1, null);
    }

    void write(byte[] pcm) {
        tx(2, pcm);
    }

    void stop() {
        tx(3, null);
    }

    void cancel() {
        tx(4, null);
    }

    void release(Context ctx) {
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        binder = null;
    }

    private void tx(int code, byte[] audio) {
        IBinder b = binder;
        if (b == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC);
            if (audio != null) {
                data.writeByteArray(audio);
                data.writeInt(audio.length);
            }
            b.transact(code, data, reply, 0);
        } catch (Throwable t) {
            Log.w(TAG, "transact " + code + " failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
