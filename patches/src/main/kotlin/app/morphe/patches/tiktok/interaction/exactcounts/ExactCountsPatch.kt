/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.exactcounts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

private const val EXACT_COUNTS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feed/ExactCounts;"

/**
 * TikTok's count formatters: static, a long in and a string out, rounding at 10,000 to "K", at a
 * million to "M" and at a billion to "B". The main one (X/0EYv on 47.1.4, X/0EYr on 47.1.3, X/0G3T
 * on 47.0.3) has about 200 callers, the like, comment, share and save counts on the feed and the
 * profile grid among them, and each build keeps one to three smaller copies of it. All of them
 * carry the "1.0M" and "1.0B" they print at the edge of a unit.
 */
internal fun Method.isCountFormatter(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" &&
        parameterTypes.map { it.toString() } == listOf("J") &&
        // The hook needs one register below the long's pair.
        (implementation?.registerCount ?: 0) > 2

internal object CountFormatterFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("J"),
    filters = listOf(string("1.0M"), string("1.0B")),
    custom = { method, _ -> method.isCountFormatter() },
)

/** How many count formatters a build may have: the main one and up to three copies. */
internal val COUNT_FORMATTERS = 2..4

@Suppress("unused")
val showExactCountsPatch = bytecodePatch(
    name = "Show exact counts",
    description = "Shows counts as full numbers, like 1,234,567 instead of 1.2M. Switch: Hushfeed settings > Feed screen.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableExactCounts()V",
        )

        val formatters = CountFormatterFingerprint.matchAll()
        if (formatters.size !in COUNT_FORMATTERS) {
            throw PatchException(
                "Show exact counts: ${formatters.size} count formatters: ${formatters.map { it.originalClassDef.type }}",
            )
        }
        formatters.forEach { match ->
            val method = match.method
            // p0 and p1 hold the long. v0 is a local the method writes before it reads.
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0, p1}, $EXACT_COUNTS_DESCRIPTOR->format(J)Ljava/lang/String;
                    move-result-object v0
                    if-eqz v0, :tiktok
                    return-object v0
                """,
                ExternalLabel("tiktok", method.getInstruction(0)),
            )
        }
    }
}
