/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Show how many were filtered, on a plain activity standing in for TikTok's main one. It has no
 * Home tab, which FeedVisibility reads as the feed, so the label shows whenever the switch and
 * the count say it should.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FilteredCountPillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean feedFilterWas;
    private ActivityController<Activity> controller;

    @Before
    public void setUp() {
        feedFilterWas = SettingsStatus.feedFilterEnabled;
        SettingsStatus.feedFilterEnabled = true;
        Settings.FILTERED_COUNT_PILL.save(false);
        FeedFilterCounters.resetSessionForTests();
        controller = Robolectric.buildActivity(Activity.class).create();
        FilteredCountPill.install(controller.get());
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        Settings.FILTERED_COUNT_PILL.resetToDefault();
        SettingsStatus.feedFilterEnabled = feedFilterWas;
        FeedFilterCounters.resetSessionForTests();
    }

    /** One layout pass of the window, which is what moves the label on a phone. */
    private void layout() {
        controller.get().getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    @Test
    public void theLabelCountsWhatTheFilterTookOutOnlyWithTheSwitchOn() {
        FeedFilterCounters.removed("FeedItemList", 3, "AdsFilter");
        controller.start().resume().visible();
        assertNull("the switch is off", FilteredCountPill.pillForTests());

        Settings.FILTERED_COUNT_PILL.save(true);
        controller.pause().resume();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("3 filtered out", pill.getText().toString());
        assertFalse("a tap goes through to the video", pill.isClickable());

        FeedFilterCounters.removed("SearchAds", 1, "searchAd");
        layout();
        assertEquals("4 filtered out", pill.getText().toString());

        Settings.FILTERED_COUNT_PILL.save(false);
        layout();
        assertEquals(View.GONE, pill.getVisibility());
        controller.pause().resume();
        assertNull("off takes it off the feed at the next resume", FilteredCountPill.pillForTests());
        assertNull(pill.getParent());
    }

    @Test
    public void nothingShowsUntilTheFilterTakesSomethingOut() {
        Settings.FILTERED_COUNT_PILL.save(true);
        controller.start().resume().visible();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        assertEquals(View.GONE, pill.getVisibility());

        FeedFilterCounters.removed("FeedItemList", 1, "AdsFilter");
        layout();
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("1 filtered out", pill.getText().toString());
    }

    @Test
    public void withoutTheFeedFilterInTheBundleNothingIsDrawn() {
        SettingsStatus.feedFilterEnabled = false;
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removed("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        assertNull(FilteredCountPill.pillForTests());
    }

    @Test
    public void closingTheActivityTakesTheLabelWithIt() {
        Settings.FILTERED_COUNT_PILL.save(true);
        FeedFilterCounters.removed("FeedItemList", 2, "AdsFilter");
        controller.start().resume().visible();
        TextView pill = FilteredCountPill.pillForTests();
        assertNotNull(pill);
        controller.pause().stop().destroy();
        assertNull(FilteredCountPill.pillForTests());
        assertNull(pill.getParent());
        controller = Robolectric.buildActivity(Activity.class).create().start().resume();
    }
}
