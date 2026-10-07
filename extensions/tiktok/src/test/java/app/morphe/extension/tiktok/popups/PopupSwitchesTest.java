/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Block popups' Pop Suite campaigns and its LIVE bubble switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PopupSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for IPopSuiteManagerService$PopupConfigObject, whose popupName is a public final field. */
    public static final class Campaign {
        public final String popupName;
        Campaign(String popupName) { this.popupName = popupName; }
    }

    /** A config shape the hook doesn't know. */
    public static final class Unnamed {
    }

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.HIDE_LIVE_BUBBLE.resetToDefault();
        Settings.POPUP_LABEL_PICKS.save("");
        Settings.POPUP_LABEL_CATALOG.save("");
        PopupLabels.forgetSeen();
    }

    private static List<String> catalog() {
        return new ArrayList<>(PopupLabelCatalog.labels());
    }

    @Test public void aCampaignIsListedAndShownUntilTicked() {
        Campaign upsell = new Campaign("tns_upsell_sheet");
        assertSame(upsell, PopupSwitches.campaign(upsell));
        assertEquals(Arrays.asList("tns_upsell_sheet"), catalog());

        Settings.POPUP_LABEL_PICKS.save("tns_upsell_sheet");
        assertNull("a ticked campaign comes back as nothing to show", PopupSwitches.campaign(upsell));
        Campaign other = new Campaign("creator_tools_intro");
        assertSame("only the ticked one", other, PopupSwitches.campaign(other));
    }

    @Test public void aCampaignTheAccountHasToAnswerIsNeverListedOrDropped() {
        Settings.POPUP_LABEL_PICKS.save("account_security_notice, verify_email_prompt");
        Campaign security = new Campaign("account_security_notice");
        Campaign verify = new Campaign("verify_email_prompt");
        assertSame(security, PopupSwitches.campaign(security));
        assertSame(verify, PopupSwitches.campaign(verify));
        assertEquals(new ArrayList<String>(), catalog());
    }

    @Test public void pausedEveryCampaignShows() {
        Settings.POPUP_LABEL_PICKS.save("tns_upsell_sheet");
        PausedProcess.set(true);
        Campaign upsell = new Campaign("tns_upsell_sheet");
        assertSame(upsell, PopupSwitches.campaign(upsell));
    }

    @Test public void nothingToShowStaysNothingAndAnUnknownShapeShows() {
        assertNull(PopupSwitches.campaign(null));
        Unnamed unnamed = new Unnamed();
        assertSame(unnamed, PopupSwitches.campaign(unnamed));
        Campaign blank = new Campaign(null);
        assertSame(blank, PopupSwitches.campaign(blank));
        assertEquals(new ArrayList<String>(), catalog());
    }

    @Test public void theLiveBubbleShowsUntilTurnedOff() {
        assertFalse(PopupSwitches.hideLiveBubble());
        Settings.HIDE_LIVE_BUBBLE.save(true);
        assertTrue(PopupSwitches.hideLiveBubble());
        PausedProcess.set(true);
        assertFalse("pausing brings it back", PopupSwitches.hideLiveBubble());
    }
}
