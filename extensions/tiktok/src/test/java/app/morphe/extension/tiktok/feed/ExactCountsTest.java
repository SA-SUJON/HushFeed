package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExactCountsTest {
    private Locale locale;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
    }

    @After public void tearDown() {
        Locale.setDefault(locale);
        Settings.SHOW_EXACT_COUNTS.save(false);
    }

    @Test public void theSwitchOffLeavesEveryCountToTikTok() {
        Settings.SHOW_EXACT_COUNTS.save(false);
        assertNull(ExactCounts.format(1_234_567L));
        assertNull(ExactCounts.format(12_345L));
    }

    @Test public void theSwitchOnGivesTheWholeNumberInThePhonesGrouping() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        Locale.setDefault(Locale.US);
        assertEquals("1,234,567", ExactCounts.format(1_234_567L));
        assertEquals("2,500,000,000", ExactCounts.format(2_500_000_000L));
        assertEquals("999", ExactCounts.format(999L));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1.234.567", ExactCounts.format(1_234_567L));
    }

    @Test public void aNegativeCountStaysTikToksToTurnIntoZero() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        assertNull(ExactCounts.format(-1L));
    }

    /** #552: a supporter's score past a million shows whole, in the phone's grouping. */
    @Test public void aSupportersScoreShowsWholeWithTheSwitchOn() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        Locale.setDefault(Locale.US);
        assertEquals("1,234,567", ExactCounts.supporterScore(1_234_567L));
        assertEquals("3,000,000", ExactCounts.supporterScore(3_000_000L));
        assertEquals("12,345", ExactCounts.supporterScore(12_345L));
        assertEquals("7", ExactCounts.supporterScore(7L));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1.234.567", ExactCounts.supporterScore(1_234_567L));
    }

    @Test public void aRowWithNoScoreOrTheSwitchOffKeepsTikToksText() {
        Settings.SHOW_EXACT_COUNTS.save(true);
        assertNull("no score of its own: TikTok's text", ExactCounts.supporterScore(0L));
        assertNull(ExactCounts.supporterScore(-5L));
        Settings.SHOW_EXACT_COUNTS.save(false);
        assertNull(ExactCounts.supporterScore(1_234_567L));
    }
}
