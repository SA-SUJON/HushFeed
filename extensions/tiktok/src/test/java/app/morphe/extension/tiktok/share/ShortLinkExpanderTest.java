/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class ShortLinkExpanderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SHORT = "https://vt.tiktok.com/ZS123/";
    private static final String VIDEO = "https://www.tiktok.com/@nasa/video/7312345678901234567";

    @After public void tearDown() {
        Settings.EXPAND_SHORT_SHARE_LINKS.resetToDefault();
        ShortLinkExpander.forgetForTests();
    }

    @Test public void aShortLinkLeadsToTheVideo() {
        Map<String, String> landings = new HashMap<>();
        landings.put(SHORT, VIDEO + "?_r=1&u_code=abc");
        landings.put("https://vm.tiktok.com/ZM456/", "https://m.tiktok.com/@nasa/video/7312345678901234567");
        assertEquals(VIDEO + "?_r=1&u_code=abc", ShortLinkExpander.expand(SHORT, landings::get));
        assertEquals("https://m.tiktok.com/@nasa/video/7312345678901234567",
                ShortLinkExpander.expand("https://vm.tiktok.com/ZM456/", landings::get));
        assertNull("a full link isn't opened", ShortLinkExpander.expand(VIDEO, url -> {
            throw new AssertionError("opened " + url);
        }));
    }

    @Test public void onlyALandingOnTikToksOwnSiteIsTaken() {
        Map<String, String> landings = new HashMap<>();
        landings.put(SHORT, "https://example.com/@nasa/video/1");
        assertNull("a landing off TikTok", ShortLinkExpander.expand(SHORT, landings::get));
        landings.put(SHORT, "http://www.tiktok.com/@nasa/video/1");
        assertNull("a landing over plain HTTP", ShortLinkExpander.expand(SHORT, landings::get));
        landings.put(SHORT, SHORT);
        assertNull("no redirect at all", ShortLinkExpander.expand(SHORT, landings::get));
        landings.put(SHORT, "https://m.tiktok.com/v/7312345678901234567.html");
        assertNull("TikTok's old video page, which names no account", ShortLinkExpander.expand(SHORT, landings::get));
        landings.clear();
        assertNull("nothing came back", ShortLinkExpander.expand(SHORT, landings::get));
        assertNull("the open failed", ShortLinkExpander.expand(SHORT, url -> {
            throw new IOException("offline");
        }));

        assertTrue(ShortLinkExpander.isShort(SHORT));
        assertTrue(ShortLinkExpander.isShort("https://vm.tiktok.com/ZM456/?share=1"));
        assertFalse(ShortLinkExpander.isShort(VIDEO));
        assertFalse(ShortLinkExpander.isShort("http://vt.tiktok.com/ZS123/"));
        assertFalse(ShortLinkExpander.isShort("https://vt.tiktok.com.example.com/ZS123/"));
    }

    @Test public void onlyTheSwitchStartsIt() {
        assertFalse(ShortLinkExpander.wants(SHORT));
        Settings.EXPAND_SHORT_SHARE_LINKS.save(true);
        assertTrue(ShortLinkExpander.wants(SHORT));
        assertFalse(ShortLinkExpander.wants(VIDEO));
        assertFalse(ShortLinkExpander.wants(null));
    }

    @Test public void theFullLinkTakesTheShortOnesPlaceOnlyOnTheClipboard() {
        Context context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = clipboard(context);

        clipboard.setPrimaryClip(ClipData.newPlainText("link", SHORT));
        ShortLinkExpander.watch(context, SHORT, VIDEO);
        idle();
        assertEquals(VIDEO, text(clipboard));
        assertEquals("Full link copied", ShadowToast.getTextOfLatestToast());

        // Copied before the link is: left alone, and the link is swapped once it arrives.
        String other = "https://vt.tiktok.com/ZS9/";
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "hello"));
        ShortLinkExpander.watch(context, other, VIDEO);
        idle();
        assertEquals("hello", text(clipboard));
        clipboard.setPrimaryClip(ClipData.newPlainText("link", "Look: " + other));
        idle();
        assertEquals("Look: " + VIDEO, text(clipboard));

        // One swap per link made: copying it again later stays as copied.
        clipboard.setPrimaryClip(ClipData.newPlainText("link", other));
        idle();
        assertEquals(other, text(clipboard));
    }

    @Test public void theWaitForCopyLinkEnds() {
        Context context = RuntimeEnvironment.getApplication();
        ClipboardManager clipboard = clipboard(context);
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "hello"));
        ShortLinkExpander.watch(context, SHORT, VIDEO);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShortLinkExpander.WATCH_MS + 1));
        clipboard.setPrimaryClip(ClipData.newPlainText("link", SHORT));
        idle();
        assertEquals(SHORT, text(clipboard));
    }

    private static ClipboardManager clipboard(Context context) {
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private static String text(ClipboardManager clipboard) {
        return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
