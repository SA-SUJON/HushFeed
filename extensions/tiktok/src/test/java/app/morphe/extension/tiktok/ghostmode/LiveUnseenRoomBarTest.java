package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.live.base.model.ImageModel;
import com.bytedance.android.live.base.model.user.User;
import com.bytedance.android.livesdkapi.depend.model.live.Room;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** #554: a LIVE watched unseen still shows its host, how long it's been on and how many watch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LiveUnseenRoomBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String ENTER = "https://webcast16-normal-useast5.tiktokv.us/webcast/room/enter/?room_id=7571234567890123456&aid=1233";
    private static final String ROOM = "7571234567890123456";
    /** 2026-11-18, about a year after {@link #ROOM}'s ID was made. */
    private static final long NOW = 1_795_000_000_000L;

    private boolean installed;
    private Locale locale;

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        LiveUnseen.resetForTests();
        LiveUnseenButton.resetForTests();
    }

    @After public void tearDown() {
        SettingsStatus.ghostModeEnabled = installed;
        Settings.GHOST_MODE.resetToDefault();
        Settings.GHOST_HIDE_LIVE_VIEWS.resetToDefault();
        Settings.SHOW_EXACT_COUNTS.resetToDefault();
        Locale.setDefault(locale);
        LiveUnseen.resetForTests();
        LiveUnseenButton.resetForTests();
    }

    private static void on() {
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(true);
    }

    @Test public void viewerCountsShortenTheWayTikToksDoAndNeverRoundUp() {
        assertEquals("999", LiveUnseenRoomBar.viewerCount(999, false, Locale.US));
        assertEquals("1K", LiveUnseenRoomBar.viewerCount(1_000, false, Locale.US));
        assertEquals("1.2K", LiveUnseenRoomBar.viewerCount(1_299, false, Locale.US));
        assertEquals("12.3K", LiveUnseenRoomBar.viewerCount(12_345, false, Locale.US));
        assertEquals("999.9K", LiveUnseenRoomBar.viewerCount(999_999, false, Locale.US));
        assertEquals("1M", LiveUnseenRoomBar.viewerCount(1_000_000, false, Locale.US));
        assertEquals("2.5M", LiveUnseenRoomBar.viewerCount(2_599_999, false, Locale.US));
        assertEquals("3B", LiveUnseenRoomBar.viewerCount(3_000_000_000L, false, Locale.US));
        assertEquals("1,2K", LiveUnseenRoomBar.viewerCount(1_234, false, Locale.GERMANY));
        assertEquals("1,234,567", LiveUnseenRoomBar.viewerCount(1_234_567, true, Locale.US));
        assertEquals("1.234.567", LiveUnseenRoomBar.viewerCount(1_234_567, true, Locale.GERMANY));
        assertEquals("12", LiveUnseenRoomBar.viewerCount(12, true, Locale.US));
    }

    @Test public void theLineSaysHowLongTheLiveHasBeenOnAndHowManyWatch() {
        Room room = new Room();
        room.idStr = ROOM;
        room.createTime = NOW / 1000 - (3600 + 2 * 60 + 3);
        room.userCount = 1_234;
        assertEquals("LIVE for 1:02:03 · 1.2K watching", LiveUnseenRoomBar.line(room, NOW, " · "));

        // Whole with Show exact counts on, and one part to a line in the small window.
        Settings.SHOW_EXACT_COUNTS.save(true);
        assertEquals("LIVE for 1:02:03\n1,234 watching", LiveUnseenRoomBar.line(room, NOW, "\n"));
        Settings.SHOW_EXACT_COUNTS.resetToDefault();

        room.userCount = 0;
        assertEquals("no count, no count part", "LIVE for 1:02:03", LiveUnseenRoomBar.line(room, NOW, " · "));

        // The room's start time, in seconds or milliseconds, then its start.
        room.createTime = NOW - 5_000;
        assertEquals("LIVE for 0:05", LiveUnseenRoomBar.line(room, NOW, " · "));
        room.createTime = 0;
        room.startTime = NOW / 1000 - 90;
        assertEquals("LIVE for 1:30", LiveUnseenRoomBar.line(room, NOW, " · "));
    }

    @Test public void aRoomFromTheFeedWithNoStartTimeIsTimedFromItsId() {
        Room room = new Room();
        long made = NOW / 1000 - 600;
        room.idStr = String.valueOf((made << 32) | 0x1234_5678L);
        room.userCount = 87;
        assertEquals(NOW - 600_000, LiveUnseenRoomBar.startedAtMs(room, NOW));
        assertEquals("LIVE for 10:00 · 87 watching", LiveUnseenRoomBar.line(room, NOW, " · "));

        // An ID that reads as more than a week ago is a misread, not a LIVE that long.
        room.idStr = ROOM;
        assertEquals(0, LiveUnseenRoomBar.startedAtMs(room, NOW));
        assertEquals("87 watching", LiveUnseenRoomBar.line(room, NOW, " · "));
        room.idStr = "12345";
        assertEquals(0, LiveUnseenRoomBar.startedAtMs(room, NOW));
        room.userCount = 0;
        assertEquals("", LiveUnseenRoomBar.line(room, NOW, " · "));
        assertEquals("", LiveUnseenRoomBar.line(null, NOW, " · "));
    }

    @Test public void theHostComesFromTheRoomTikTokLoaded() {
        Room room = new Room();
        assertNull("no owner, no host", LiveUnseenRoomBar.host(room));
        User owner = new User(7_000_000_001L, "Some Host");
        owner.username = "some.host";
        owner.avatarThumb = new ImageModel(Arrays.asList("http://images.example/plain.webp",
                "https://images.example/avatar.webp"));
        room.owner = owner;
        LiveUnseenRoomBar.Host host = LiveUnseenRoomBar.host(room);
        assertNotNull(host);
        assertEquals("Some Host", host.name);
        assertEquals("some.host", host.handle);
        assertEquals("only a secure address", "https://images.example/avatar.webp", host.picture);

        owner.nickName = " ";
        owner.avatarThumb = null;
        host = LiveUnseenRoomBar.host(room);
        assertEquals("no name, the username", "some.host", host.name);
        assertNull(host.picture);
        owner.username = null;
        assertNull(LiveUnseenRoomBar.host(room));

        assertEquals("S", LiveUnseenRoomBar.initial("some host"));
        assertEquals("", LiveUnseenRoomBar.initial(""));
    }

    @Test public void theHostsProfileOpensInTikTokWithoutTheSmallWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            Intent intent = LiveUnseenRoomBar.profileIntent(activity, "some.host");
            assertEquals(Intent.ACTION_VIEW, intent.getAction());
            assertEquals("https://www.tiktok.com/@some.host", intent.getDataString());
            assertEquals(activity.getPackageName(), intent.getPackage());
            assertTrue("leaving for the profile isn't leaving TikTok",
                    (intent.getFlags() & Intent.FLAG_ACTIVITY_NO_USER_ACTION) != 0);
            assertNull(LiveUnseenRoomBar.profileIntent(activity, "two words"));
            assertNull(LiveUnseenRoomBar.profileIntent(activity, null));
        } finally {
            activity.finish();
        }
    }

    private static LinearLayout bar(ViewGroup content) {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof LinearLayout && ((LinearLayout) child).getChildCount() == 2
                    && ((LinearLayout) child).getChildAt(1) instanceof TextView) {
                return (LinearLayout) child;
            }
        }
        return null;
    }

    @Test public void aHeldRoomShowsItsHostAndViewersAndSendsNothing() throws Exception {
        on();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        LiveUnseenTest.RoomPage page = new LiveUnseenTest.RoomPage(activity, ROOM);
        User owner = new User(7_000_000_001L, "Some Host");
        owner.username = "some.host";
        page.room.owner = owner;
        page.room.createTime = System.currentTimeMillis() / 1000 - 125;
        page.room.userCount = 1_234;
        LiveUnseenTest.Interceptor enter = new LiveUnseenTest.Interceptor(ENTER);
        try {
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(enter, "chain"));
            assertEquals(1, LiveUnseen.heldForTests());

            Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
            LiveUnseenRoomBar.refresh(activity, page);
            assertNull("nothing without Watch LIVEs unseen", bar(content));
            Settings.GHOST_HIDE_LIVE_VIEWS.save(true);

            LiveUnseenRoomBar.refresh(activity, page);
            LinearLayout bar = bar(content);
            assertNotNull(bar);
            LinearLayout pill = (LinearLayout) bar.getChildAt(0);
            TextView line = (TextView) bar.getChildAt(1);
            assertEquals(View.VISIBLE, bar.getVisibility());
            assertEquals(View.VISIBLE, pill.getVisibility());
            assertEquals("Some Host", ((TextView) pill.getChildAt(1)).getText().toString());
            ViewGroup picture = (ViewGroup) pill.getChildAt(0);
            assertEquals("no TikTok image view here, so the initial", 1, picture.getChildCount());
            assertEquals("S", ((TextView) picture.getChildAt(0)).getText().toString());
            assertEquals("Open Some Host's profile", pill.getContentDescription().toString());
            String text = line.getText().toString();
            assertTrue(text, text.startsWith("LIVE for 2:0") && text.endsWith(" · 1.2K watching"));

            // A tap opens the host's profile, and the room's held enter stays held.
            assertTrue(pill.performClick());
            Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals("https://www.tiktok.com/@some.host", started.getDataString());
            assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NO_USER_ACTION) != 0);
            assertTrue("nothing was sent", enter.sentWith.isEmpty());
            assertEquals(1, LiveUnseen.heldForTests());

            // Another screen over the room takes the bar away until the room is back.
            LiveUnseenRoomBar.paused(activity);
            assertNull(bar(content));
            LiveUnseenRoomBar.refresh(activity, page);
            assertNull("the tick doesn't bring it back while paused", bar(content));
            LiveUnseenRoomBar.resumed(activity);
            LiveUnseenRoomBar.refresh(activity, page);
            assertNotNull(bar(content));

            // In the small window the line stays alone, a part to a line.
            activity.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            LiveUnseenRoomBar.paused(activity);
            LiveUnseenRoomBar.refresh(activity, page);
            bar = bar(content);
            assertNotNull("the room goes on in the small window", bar);
            assertEquals(View.GONE, bar.getChildAt(0).getVisibility());
            text = ((TextView) bar.getChildAt(1)).getText().toString();
            assertTrue(text, text.startsWith("LIVE for 2:0") && text.endsWith("\n1.2K watching"));

            // Once TikTok's own enter builds the room, its own host bar is back and this goes.
            Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
            LiveUnseen.holdEnter(new LiveUnseenTest.Interceptor(ENTER), "chain");
            Settings.GHOST_HIDE_LIVE_VIEWS.save(true);
            assertFalse(LiveUnseen.unbuilt(ROOM));
            LiveUnseenRoomBar.refresh(activity, page);
            assertNull(bar(content));
        } finally {
            activity.finish();
        }
    }

    @Test public void aHeldRoomNamingNoOneAndNoCountsDrawsNothing() {
        on();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        try {
            int before = content.getChildCount();
            LiveUnseenRoomBar.refresh(activity, new LiveUnseenTest.RoomPage(activity, ROOM));
            assertEquals(before, content.getChildCount());
            LiveUnseenRoomBar.refresh(activity, null);
            assertEquals(before, content.getChildCount());
        } finally {
            activity.finish();
        }
    }
}
