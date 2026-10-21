package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Handler;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import java.util.Collections;
import java.util.List;

/**
 * Guards around APIs the TV's Android 6.0 (API 23) predates, plus the firmware quirks of a
 * China-market set. The launcher targets modern Android TV boxes, so every call that needs a
 * newer platform or a service this firmware does not run goes through here and degrades
 * gracefully instead of throwing.
 */
final class Compat {

    /** TvInputManager only defines CONNECTED and DISCONNECTED; -1 means this set cannot tell. */
    static final int UNKNOWN_STATE = -1;

    private Compat() {}

    /** API 24: let a view skip the offscreen buffer when its layers overlap. */
    static void noOverlappingRendering(View view) {
        if (Build.VERSION.SDK_INT >= 24) view.forceHasOverlappingRendering(false);
    }

    /** API 24: inputs the user hid in Settings &gt; Inputs. Older releases have no such flag. */
    static boolean isHidden(TvInputInfo info, Context context) {
        return Build.VERSION.SDK_INT >= 24 && info.isHidden(context);
    }

    /** API 24: the name the user gave an input or a CEC device. Absent before Android 7. */
    static CharSequence customLabel(TvInputInfo info, Context context) {
        return Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(context) : null;
    }

    /** API 26: a font resource. ResourcesCompat parses the font XML on older releases. */
    static Typeface font(Context context, int id) {
        if (Build.VERSION.SDK_INT >= 26) return context.getResources().getFont(id);
        return ResourcesCompat.getFont(context, id);
    }

    /**
     * The TV Input Framework is a separate system service. Firmware that never starts it still
     * hands out a TvInputManager whose binder is null, so the call throws NullPointerException
     * rather than returning an empty list.
     */
    static List<TvInputInfo> tvInputs(TvInputManager tim) {
        if (tim == null) return Collections.emptyList();
        try {
            return tim.getTvInputList();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    static void watchTvInputs(TvInputManager tim, TvInputManager.TvInputCallback callback, Handler handler) {
        if (tim == null) return;
        try {
            tim.registerCallback(callback, handler);
        } catch (RuntimeException ignored) {
            // No TV Input Framework on this set: the Inputs pill falls back to the source panel.
        }
    }

    static void unwatchTvInputs(TvInputManager tim, TvInputManager.TvInputCallback callback) {
        if (tim == null) return;
        try {
            tim.unregisterCallback(callback);
        } catch (RuntimeException ignored) {
        }
    }

    static int tvInputState(TvInputManager tim, String inputId) {
        if (tim == null) return UNKNOWN_STATE;
        try {
            return tim.getInputState(inputId);
        } catch (RuntimeException e) {
            return UNKNOWN_STATE;
        }
    }

    /** TCL sets register none of the standard Settings actions; fall back to their own entry activity. */
    static Intent settingsIntent(Context ctx, Intent target) {
        if (target.resolveActivity(ctx.getPackageManager()) != null) return target;
        Intent tcl = new Intent().setComponent(
                new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"));
        if (tcl.resolveActivity(ctx.getPackageManager()) != null) return tcl;
        return target;
    }

    /** Standard settings actions resolve to nothing here, but startActivity fails late (Error type 3),
     *  so ask the platform by trying and fall back to TCL's own settings entry. */
    static boolean startWithFallback(Context ctx, Intent target) {
        try {
            ctx.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException ignored) {
            // fall through
        }
        Intent tcl = new Intent().setComponent(
                new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"));
        try {
            ctx.startActivity(tcl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
