/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The stand-ins Turn off haptics puts in place of TikTok's own haptic and vibrator calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HapticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A view that counts the haptics it was asked for instead of playing them. */
    private static final class CountingView extends View {
        int haptics;

        CountingView(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            haptics++;
            return true;
        }

        @Override public boolean performHapticFeedback(int feedbackConstant, int flags) {
            haptics++;
            return true;
        }
    }

    private CountingView view;
    private Vibrator vibrator;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        view = new CountingView(context);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @After public void tearDown() {
        Settings.TURN_OFF_HAPTICS.resetToDefault();
    }

    @Test public void theSwitchStartsOnAndTikToksHapticsDontPlay() {
        assertTrue("picking the patch is the ask", Settings.TURN_OFF_HAPTICS.get());

        assertFalse("the view call answers as a view with haptics off does",
                Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertFalse(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals("the view was never asked", 0, view.haptics);

        Haptics.vibrate(vibrator, 30L);
        assertFalse("a timed vibration played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE));
        assertFalse("an effect played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
        assertFalse("an effect with attributes played", shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffTheyPlayAsTikTokAsked() {
        Settings.TURN_OFF_HAPTICS.save(false);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals(2, view.haptics);

        Haptics.vibrate(vibrator, 30L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(30L, shadowOf(vibrator).getMilliseconds());
    }

    @Test public void switchedOffAnEffectPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE));
        assertTrue(shadowOf(vibrator).isVibrating());
    }
}
