/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** #479: the two comment sheet experiments, answered on the App AB int getter's way out. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class CommentSheetGatesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String FULL_PAGE = "comment_panel_full_page";
    private static final String SHRINK = "video_shrink_by_comment";

    private boolean oldTools;

    @Before public void setUp() {
        oldTools = SettingsStatus.commentToolsEnabled;
        SettingsStatus.commentToolsEnabled = true;
        Settings.SHOW_COMMENT_EXPAND_BUTTON.save(false);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(false);
        Settings.KEEP_VIDEO_SIZE_UNDER_COMMENTS.save(false);
    }

    @After public void tearDown() {
        SettingsStatus.commentToolsEnabled = oldTools;
        Settings.SHOW_COMMENT_EXPAND_BUTTON.save(false);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(false);
        Settings.KEEP_VIDEO_SIZE_UNDER_COMMENTS.save(false);
    }

    @Test public void bothSwitchesOffLeaveEveryValueAsTikTokGaveIt() {
        for (int value : new int[]{-1, 0, 1, 2, 3}) {
            assertEquals(value, CommentSheetGates.answer(FULL_PAGE, value));
            assertEquals(value, CommentSheetGates.answer(SHRINK, value));
        }
    }

    @Test public void theExpandButtonIsBuiltForEveryAccountWhileTheSwitchIsOn() {
        Settings.SHOW_COMMENT_EXPAND_BUTTON.save(true);
        // 0 builds no button and 2 builds the pull gesture instead of one; 1 is the button.
        assertEquals(1, CommentSheetGates.answer(FULL_PAGE, 0));
        assertEquals(1, CommentSheetGates.answer(FULL_PAGE, 2));
        assertEquals(1, CommentSheetGates.answer(FULL_PAGE, 1));
        // Only that key: the shrink and an unrelated int keep their values.
        assertEquals(-1, CommentSheetGates.answer(SHRINK, -1));
        assertEquals(0, CommentSheetGates.answer("comment_panel_full_page_v2", 0));
        assertEquals(0, CommentSheetGates.answer(null, 0));
    }

    @Test public void hidingTheExpandButtonWinsOverShowingIt() {
        Settings.SHOW_COMMENT_EXPAND_BUTTON.save(true);
        Settings.HIDE_COMMENT_EXPAND_BUTTON.save(true);
        assertEquals(0, CommentSheetGates.answer(FULL_PAGE, 0));
        assertEquals(2, CommentSheetGates.answer(FULL_PAGE, 2));
    }

    @Test public void theVideoKeepsItsSizeOnlyWhereTikTokWouldShrinkIt() {
        Settings.KEEP_VIDEO_SIZE_UNDER_COMMENTS.save(true);
        // Only 1 turns the shrink on; the default -1 and any other value already keep the size.
        assertEquals(0, CommentSheetGates.answer(SHRINK, 1));
        assertEquals(-1, CommentSheetGates.answer(SHRINK, -1));
        assertEquals(2, CommentSheetGates.answer(SHRINK, 2));
        assertEquals(0, CommentSheetGates.answer(FULL_PAGE, 0));
    }

    @Test public void withoutCommentToolsNothingIsAnswered() {
        Settings.SHOW_COMMENT_EXPAND_BUTTON.save(true);
        Settings.KEEP_VIDEO_SIZE_UNDER_COMMENTS.save(true);
        SettingsStatus.commentToolsEnabled = false;
        assertEquals(0, CommentSheetGates.answer(FULL_PAGE, 0));
        assertEquals(1, CommentSheetGates.answer(SHRINK, 1));
    }

    /**
     * #556: TikTok stretches the sheet to the window's height less 8dp (24 px at 3x), measured
     * from bounds that take in the status bar, so on a 2992 px tall edge-to-edge window its top
     * landed 24 px down, under a 150 px status bar. It now stops the same 24 px below the bar.
     */
    @Test public void theExpandedSheetsTopStaysTheGapBelowTheStatusBar() {
        int window = 2992, gap = 24, statusBar = 150;
        int tikTok = window - gap;
        int fitted = CommentSheetGates.belowTopBar(tikTok, gap, window, statusBar);
        assertEquals(window - statusBar - gap, fitted);
        assertEquals("the top lands the gap below the bar", statusBar + gap, window - fitted);

        // A sheet held above a 3-button bar keeps its bottom there and still stops below the clock.
        int aboveNavBar = window - 144;
        assertEquals(aboveNavBar - 72 - gap, CommentSheetGates.belowTopBar(tikTok, gap, aboveNavBar, 72));
    }

    @Test public void aSheetThatAlreadyFitsIsLeftAsTikTokAskedAndNeverGrows() {
        assertEquals(2000, CommentSheetGates.belowTopBar(2000, 24, 2992, 150));
        assertEquals("exactly at the limit", 2818, CommentSheetGates.belowTopBar(2818, 24, 2992, 150));
        assertEquals("no status bar to stay under", 2968, CommentSheetGates.belowTopBar(2968, 24, 2992, 0));
        assertEquals("a gap below zero counts as none", 2842, CommentSheetGates.belowTopBar(2968, -5, 2992, 150));
    }

    @Test public void numbersThatCantBeRightLeaveTikToksHeight() {
        assertEquals("not laid out", 2968, CommentSheetGates.belowTopBar(2968, 24, 0, 150));
        assertEquals("bottom above the bar", 2968, CommentSheetGates.belowTopBar(2968, 24, 100, 150));
        assertEquals("nothing asked", 0, CommentSheetGates.belowTopBar(0, 24, 2992, 150));
    }

    @Test public void aSheetNotOnScreenKeepsTikToksHeight() {
        assertEquals(2968, CommentSheetGates.expandedHeight(null, 2968, 24));
        android.view.View loose = new android.view.View(org.robolectric.RuntimeEnvironment.getApplication());
        assertEquals("not attached to a window", 2968, CommentSheetGates.expandedHeight(loose, 2968, 24));
    }
}
