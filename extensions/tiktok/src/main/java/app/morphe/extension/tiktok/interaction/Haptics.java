/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * What the Turn off haptics patch asks each time TikTok plays a vibration of its own.
 *
 * <p>TikTok 47 plays its taps and gestures three ways: about sixty calls to
 * {@code View.performHapticFeedback}, about thirty that hand the vibrator an effect, and about
 * fifty timed ones, {@code vibrate(long)}. Most timed ones run 6 to 100 ms and the rest up to half
 * a second: the older-Android branch beside an effect call, a comment like, a dislike, a shake
 * ad, a LIVE long press. The patch sends every one of those calls here. While the switch is on
 * they don't play, and the View call answers false, as Android does for a view whose haptics are
 * off. A patterned vibration, {@code vibrate(long[], int)}, is what an alert uses, so the patch
 * leaves those alone. A notification Android shows buzzes through its channel, and the keyboard
 * and the phone play their own haptics, so none of those pass through here. The buzz Android plays
 * after a long click listener answers does pass through, by way of the listener TikTok sets.
 *
 * <p>Off, paused, or a failure in here, and they play as TikTok asked.
 */
public final class Haptics {
    private Haptics() {
    }

    /** Injection point, in place of each of TikTok's {@code View.performHapticFeedback(int)} calls. */
    public static boolean performHapticFeedback(View view, int feedbackConstant) {
        if (holdsBack("view haptic")) return false;
        return view.performHapticFeedback(feedbackConstant);
    }

    /** Injection point, in place of each of TikTok's {@code View.performHapticFeedback(int, int)} calls. */
    public static boolean performHapticFeedback(View view, int feedbackConstant, int flags) {
        if (holdsBack("view haptic")) return false;
        return view.performHapticFeedback(feedbackConstant, flags);
    }

    /** Injection point, in place of each of TikTok's {@code Vibrator.vibrate(long)} calls. */
    @SuppressWarnings("deprecation")
    public static void vibrate(Vibrator vibrator, long milliseconds) {
        if (holdsBack("timed vibration")) return;
        vibrator.vibrate(milliseconds);
    }

    /** Injection point, in place of each of TikTok's {@code Vibrator.vibrate(VibrationEffect)} calls. */
    @RequiresApi(26)
    public static void vibrate(Vibrator vibrator, VibrationEffect effect) {
        if (holdsBack("vibration")) return;
        vibrator.vibrate(effect);
    }

    /** Injection point, in place of each {@code Vibrator.vibrate(VibrationEffect, AudioAttributes)} call. */
    @RequiresApi(26)
    @SuppressWarnings("deprecation")
    public static void vibrate(Vibrator vibrator, VibrationEffect effect, AudioAttributes attributes) {
        if (holdsBack("vibration")) return;
        vibrator.vibrate(effect, attributes);
    }

    /**
     * Injection point, in place of each of TikTok's {@code View.setOnLongClickListener} calls.
     *
     * <p>Android plays the long press buzz itself: once a long click listener answers true, the
     * view asks for LONG_PRESS straight away, in framework code no patch reaches. The listener is
     * wrapped instead, and while the switch is on the wrapper turns that view's haptics off for
     * the moment after it answers, then back on with the next message. A view whose haptics were
     * already off is left as it was. A null listener stays null, so clearing one still clears it.
     */
    public static void setOnLongClickListener(View view, View.OnLongClickListener listener) {
        view.setOnLongClickListener(listener == null ? null : new QuietLongClick(listener));
    }

    static final class QuietLongClick implements View.OnLongClickListener {
        final View.OnLongClickListener listener;

        QuietLongClick(View.OnLongClickListener listener) {
            this.listener = listener;
        }

        @Override public boolean onLongClick(View view) {
            boolean handled = listener.onLongClick(view);
            if (handled && view.isHapticFeedbackEnabled() && holdsBack("long press")) {
                view.setHapticFeedbackEnabled(false);
                view.post(() -> view.setHapticFeedbackEnabled(true));
            }
            return handled;
        }
    }

    /** True when the switch holds back the haptic TikTok asked for. Never throws. */
    static boolean holdsBack(String what) {
        try {
            if (!Settings.TURN_OFF_HAPTICS.get()) return false;
            Logger.printDebug(() -> "Turn off haptics: held back a " + what);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
