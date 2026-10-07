/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Block popups' two switches beside the checklist. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PopupSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.HIDE_2SV_SUGGESTION.resetToDefault();
        Settings.HIDE_LIVE_BUBBLE.resetToDefault();
    }

    @Test public void bothShowUntilTurnedOn() {
        assertFalse(PopupSwitches.hideIntroSheet());
        assertFalse(PopupSwitches.hideLiveBubble());
    }

    @Test public void eachSwitchHidesOnlyItsOwn() {
        Settings.HIDE_2SV_SUGGESTION.save(true);
        assertTrue(PopupSwitches.hideIntroSheet());
        assertFalse(PopupSwitches.hideLiveBubble());

        Settings.HIDE_2SV_SUGGESTION.save(false);
        Settings.HIDE_LIVE_BUBBLE.save(true);
        assertFalse(PopupSwitches.hideIntroSheet());
        assertTrue(PopupSwitches.hideLiveBubble());
    }

    @Test public void pausingBringsBothBack() {
        Settings.HIDE_2SV_SUGGESTION.save(true);
        Settings.HIDE_LIVE_BUBBLE.save(true);
        PausedProcess.set(true);

        assertFalse(PopupSwitches.hideIntroSheet());
        assertFalse(PopupSwitches.hideLiveBubble());
    }
}
