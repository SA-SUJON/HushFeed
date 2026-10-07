/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The stand-ins Turn off haptics puts in place of TikTok's own haptic and vibrator calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HapticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int NO_FLAGS = -1;

    /** A view that records the haptics it was asked for instead of playing them. */
    private static final class CountingView extends View {
        int haptics;
        int lastConstant = -1;
        int lastFlags = NO_FLAGS;

        CountingView(Context context) {
            super(context);
        }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = NO_FLAGS;
            return true;
        }

        @Override public boolean performHapticFeedback(int feedbackConstant, int flags) {
            haptics++;
            lastConstant = feedbackConstant;
            lastFlags = flags;
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
        PausedProcess.set(false);
        Settings.TURN_OFF_HAPTICS.resetToDefault();
    }

    private static VibrationEffect shortEffect(long millis) {
        return VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    private static AudioAttributes touchAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build();
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
        Haptics.vibrate(vibrator, shortEffect(30L));
        assertFalse("an effect played", shadowOf(vibrator).isVibrating());
        Haptics.vibrate(vibrator, shortEffect(30L), touchAttributes());
        assertFalse("an effect with attributes played", shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffTheyPlayAsTikTokAsked() {
        Settings.TURN_OFF_HAPTICS.save(false);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        assertEquals(HapticFeedbackConstants.LONG_PRESS, view.lastConstant);
        assertEquals("the one-argument call stays one", NO_FLAGS, view.lastFlags);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
        assertEquals(2, view.haptics);
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, view.lastConstant);
        assertEquals(HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING, view.lastFlags);

        Haptics.vibrate(vibrator, 30L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(30L, shadowOf(vibrator).getMilliseconds());
    }

    @Test public void switchedOffAnEffectPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L));
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void switchedOffAnEffectWithAttributesPlays() {
        Settings.TURN_OFF_HAPTICS.save(false);

        Haptics.vibrate(vibrator, shortEffect(40L), touchAttributes());
        assertTrue(shadowOf(vibrator).isVibrating());
    }

    @Test public void pausedTheyPlayWithTheSwitchOn() {
        PausedProcess.set(true);

        assertTrue(Haptics.performHapticFeedback(view, HapticFeedbackConstants.LONG_PRESS));
        assertEquals(1, view.haptics);
        Haptics.vibrate(vibrator, 25L);
        assertTrue(shadowOf(vibrator).isVibrating());
        assertEquals(25L, shadowOf(vibrator).getMilliseconds());
    }

    /** A view in a real window, so a post reaches the main looper. */
    private interface WithView {
        void run(View target);
    }

    private static void inAWindow(WithView body) {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            View target = new View(controller.get());
            controller.get().setContentView(target);
            body.run(target);
        }
    }

    @Test public void aLongPressIsQuietAndTheViewsHapticsComeBackAfter() {
        inAWindow(target -> {
            int[] presses = {0};
            Haptics.setOnLongClickListener(target, v -> {
                presses[0]++;
                return true;
            });
            assertTrue(target.performLongClick());
            assertEquals("TikTok's own listener still runs", 1, presses[0]);
            assertFalse("off for the buzz Android asks for right after", target.isHapticFeedbackEnabled());
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("back on with the next message", target.isHapticFeedbackEnabled());
        });
    }

    @Test public void aLongPressNobodyHandledLeavesTheView() {
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> false);
            target.performLongClick();
            assertTrue(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void switchedOffOrPausedALongPressKeepsItsBuzz() {
        Settings.TURN_OFF_HAPTICS.save(false);
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            assertTrue(target.isHapticFeedbackEnabled());
        });
        Settings.TURN_OFF_HAPTICS.save(true);
        PausedProcess.set(true);
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            assertTrue(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void aViewWithHapticsOffStaysOff() {
        inAWindow(target -> {
            target.setHapticFeedbackEnabled(false);
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.performLongClick());
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(target.isHapticFeedbackEnabled());
        });
    }

    @Test public void clearingTheListenerStillClearsIt() {
        inAWindow(target -> {
            Haptics.setOnLongClickListener(target, v -> true);
            assertTrue(target.hasOnLongClickListeners());
            Haptics.setOnLongClickListener(target, null);
            assertFalse(target.hasOnLongClickListeners());
        });
    }
}
