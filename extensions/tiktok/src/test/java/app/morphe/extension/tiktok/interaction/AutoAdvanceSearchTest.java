/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;

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

/** Auto-advance in search results, as TikTok's search flag reads it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutoAdvanceSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.AUTO_ADVANCE.resetToDefault();
        Settings.AUTO_ADVANCE_SEARCH.resetToDefault();
    }

    @Test public void offTheServersAnswerGoesThrough() {
        assertEquals(0, AutoAdvance.searchFlag(0));
        assertEquals(1, AutoAdvance.searchFlag(1));
        assertEquals(2, AutoAdvance.searchFlag(2));
    }

    @Test public void bothSwitchesOnTurnTheFlagOn() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        assertEquals(1, AutoAdvance.searchFlag(0));
        assertEquals(1, AutoAdvance.searchFlag(2));
    }

    @Test public void theSearchSwitchAloneDoesNothing() {
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        assertEquals("it's a child of Auto-advance videos", 0, AutoAdvance.searchFlag(0));
    }

    @Test public void autoAdvanceAloneLeavesSearchAlone() {
        Settings.AUTO_ADVANCE.save(true);
        assertEquals(0, AutoAdvance.searchFlag(0));
    }

    @Test public void pausingGivesTheServersAnswerBack() {
        Settings.AUTO_ADVANCE.save(true);
        Settings.AUTO_ADVANCE_SEARCH.save(true);
        PausedProcess.set(true);
        assertEquals(0, AutoAdvance.searchFlag(0));
    }
}
