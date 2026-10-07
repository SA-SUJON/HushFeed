package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
public class LiveControlsTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        Settings.STOP_LIVE_AUTO_ENTER.save(Settings.STOP_LIVE_AUTO_ENTER.defaultValue);
    }

    @Test public void offByDefaultPreviewsCountDownAsBefore() {
        assertFalse(Settings.STOP_LIVE_AUTO_ENTER.defaultValue);
        assertFalse(LiveControls.skipAutoEnter());
    }

    @Test public void onTheCountdownNeverStarts() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        assertTrue(LiveControls.skipAutoEnter());
    }

    @Test public void pausedPreviewsCountDownAsBefore() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        setPaused(true);
        assertFalse(LiveControls.skipAutoEnter());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveControls.skipAutoEnter());
    }

    /** Read at the call, so turning it off mid-session lets the next preview count down again. */
    @Test public void turningItOffTakesEffectAtTheNextPreview() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        assertTrue(LiveControls.skipAutoEnter());
        Settings.STOP_LIVE_AUTO_ENTER.save(false);
        assertFalse(LiveControls.skipAutoEnter());
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
