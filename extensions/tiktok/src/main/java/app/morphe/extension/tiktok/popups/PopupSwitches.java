/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The prompts Block popups can't reach through the popup layer's labels.
 *
 * <p>Pop Suite is how TikTok's server sends its own sheets and floating cards, each one a campaign
 * with a name. The popup layer task it queues for one carries no label, so the checklist never saw
 * them. The campaign's name now goes through the checklist the same way a label does: recorded the
 * first time, dropped once ticked, and never recorded or dropped when it reads like a prompt the
 * account has to answer. A dropped campaign is handed back as null, which Pop Suite already treats
 * as nothing to show.
 *
 * <p>The LIVE bubble is the one TikTok floats at the top of the feed when someone is live. It's
 * raised from LiveBubbleUtil's check, outside the popup layer altogether, and with its switch on
 * that check returns before anything shows. The switch is off until turned on, and pausing
 * Hushfeed brings the bubble back.
 */
public final class PopupSwitches {
    private static volatile Field nameField;

    private PopupSwitches() {
    }

    /**
     * Injection point, where Pop Suite picks up the campaign it's about to show. Returns the
     * campaign, or null when the reader ticked its name.
     */
    @Nullable
    public static Object campaign(@Nullable Object config) {
        if (config == null) return null;
        try {
            String name = campaignName(config);
            if (!PopupLabels.shouldDrop(name, config.getClass().getName())) return config;
            Logger.printDebug(() -> "Block popups: skipped the campaign " + name);
            return null;
        } catch (Throwable failure) {
            Logger.printException(() -> "Block popups: could not read a campaign's name", failure);
            return config;
        }
    }

    /** The campaign's popupName, or empty when it names none. */
    static String campaignName(Object config) throws ReflectiveOperationException {
        Field field = nameField;
        if (field == null || !field.getDeclaringClass().isInstance(config)) {
            field = config.getClass().getField("popupName");
            nameField = field;
        }
        Object value = field.get(config);
        return value instanceof String ? (String) value : "";
    }

    /** Injection point, at the start of the LIVE bubble's check. True skips the bubble. */
    public static boolean hideLiveBubble() {
        try {
            if (!Settings.HIDE_LIVE_BUBBLE.get()) return false;
            Logger.printDebug(() -> "Block popups: skipped the LIVE bubble");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
