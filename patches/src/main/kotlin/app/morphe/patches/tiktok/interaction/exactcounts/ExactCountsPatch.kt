/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.exactcounts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patches.tiktok.misc.settings.configurableBytecodePatch as bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

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

/**
 * Calls in this method to TikTok's general number formatter, (Number, style, I, Boolean, I) to
 * text up to 47.9.3. 48.0.2's (LX/01Tk;->LIZIZ) takes a String after the style.
 */
internal fun Method.compactNumberFormatterCalls(): Int =
    implementation?.instructions?.count { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && reference != null &&
            reference.returnType == "Ljava/lang/CharSequence;" &&
            reference.parameterTypes.map { it.toString() }.let {
                it.firstOrNull() == "Ljava/lang/Number;" &&
                    (it.drop(2) == listOf("I", "Ljava/lang/Boolean;", "I") ||
                        it.drop(2) == listOf("Ljava/lang/String;", "I", "Ljava/lang/Boolean;", "I"))
            }
    } ?: 0

/**
 * The compact count formatters, which print the like, comment, share and save counts on the feed
 * rail, and comment likes and repost counts. They don't carry "1.0M": each keeps a count under
 * 10,000 whole and hands the rest to TikTok's general number formatter, once per style. Every
 * declared build has four (X/09Fm, X/0HAE, X/0Zwb and X/0ZFf on 47.1.4; LX/08Oc, LX/0Iff, LX/0Y67
 * and LX/0jHW on 48.0.2).
 */
internal object CompactCountFormatterFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("J"),
    filters = listOf(literal(10_000L)),
    custom = { method, _ ->
        method.isCountFormatter() && method.compactNumberFormatterCalls() == 2 &&
            method.implementation!!.instructions.any { (it as? WideLiteralInstruction)?.wideLiteral == 10_000L }
    },
)

/** How many compact count formatters a build may have. */
internal val COMPACT_COUNT_FORMATTERS = 4..5

/**
 * TikTok LIVE's own count formatter class (LiveCountDisplayUtil, LX/0MCS on 48.0.2), found by the
 * log line of its gift coin formatter. Its static long-to-string formatters print the supporters
 * list's scores, gift coins and viewer counts, and none carries the "1.0M" CountFormatterFingerprint
 * looks for (#552).
 */
internal object LiveCountDisplayFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("J"),
    filters = listOf(string("getDisplayCountByNumberOfDigitsForCoin exception: ")),
    custom = { method, _ -> method.isCountFormatter() },
)

/**
 * A LIVE count formatter that rounds a count to whole K, M or B units (either case). One that
 * prints an at-least figure, 1K+ or 1M+, is left alone: the supporters list hands it another
 * viewer's score as a floor, so a whole number there would read as this viewer's own.
 */
internal fun Method.isLiveCountFormatter(): Boolean {
    if (!isCountFormatter()) return false
    val strings = implementation?.instructions?.toList().orEmpty().mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }
    return strings.any { it.equals("M", ignoreCase = true) } && strings.none { it.matches(Regex("[KMBkmb]\\+")) }
}

/** How many LIVE count formatters a build may have: six on 48.0.2. */
internal val LIVE_COUNT_FORMATTERS = 5..7

private const val ONLINE_AUDIENCE_DISPLAY_STRATEGY =
    "Lcom/bytedance/android/livesdk/livesetting/rank/OnlineAudienceDisplayStrategySetting;"

/**
 * The score text of a row in a LIVE's supporters list (LX/0MN4->LIZ on 48.0.2): static,
 * (Z, J, row) to a string. Under TikTok's display strategy a viewer past the first few shows a
 * floor like 1M+ taken from another score, or a text the server sent, instead of the row's own
 * score, so the hook answers before any of that.
 */
internal object SupporterScoreTextFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Z", "J", "L"),
    filters = listOf(methodCall(definingClass = ONLINE_AUDIENCE_DISPLAY_STRATEGY, name = "getValue")),
    // The hook needs three registers below the four parameter ones.
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && (method.implementation?.registerCount ?: 0) >= 7 },
)

/**
 * The row's own score: the long field of the row type that the method reads and hands straight
 * to a static long-to-string formatter of [formatterClass] (LX/0MMs->LIZIZ, built from
 * Rank.score, on 48.0.2). Null when no read does.
 */
internal fun Method.supporterScoreField(formatterClass: String): FieldReference? {
    val rowType = parameterTypes.last().toString()
    val instructions = implementation?.instructions?.toList() ?: return null
    return instructions.indices.firstNotNullOfOrNull { at ->
        val read = instructions[at]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return@firstNotNullOfOrNull null
        val call = instructions.getOrNull(at + 1) as? FiveRegisterInstruction ?: return@firstNotNullOfOrNull null
        val callee = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstNotNullOfOrNull null
        field.takeIf {
            read.opcode == Opcode.IGET_WIDE && field.definingClass == rowType && field.type == "J" &&
                call.opcode == Opcode.INVOKE_STATIC && callee.definingClass == formatterClass &&
                callee.returnType == "Ljava/lang/String;" && callee.parameterTypes.map { it.toString() } == listOf("J") &&
                call.registerC == (read as TwoRegisterInstruction).registerA
        }
    }
}

@Suppress("unused")
val showExactCountsPatch = bytecodePatch(
    name = "Show exact counts",
    description = "Shows likes, comments and other counts as full numbers, like 1,234,567 " +
        "instead of 1.2M. Starts off. Turn it on in Hushfeed settings > Feed screen.",
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
        val compactFormatters = CompactCountFormatterFingerprint.matchAll()
        if (compactFormatters.size !in COMPACT_COUNT_FORMATTERS) {
            throw PatchException(
                "Show exact counts: ${compactFormatters.size} compact count formatters: " +
                    "${compactFormatters.map { it.originalClassDef.type }}",
            )
        }
        // #552: LIVE's own counts, the supporters list's scores among them. On a build the patch
        // wasn't made for, LIVE counts it can't find stay as TikTok shows them.
        val declaredBuild = packageMetadata.versionName in declaredVersions()
        val coinFormatter = if (declaredBuild) LiveCountDisplayFingerprint.method else LiveCountDisplayFingerprint.methodOrNull
        val liveFormatters = coinFormatter?.let { coin ->
            mutableClassDefBy(coin.definingClass).methods.filter { it.isLiveCountFormatter() }
        }.orEmpty()
        if (declaredBuild && liveFormatters.size !in LIVE_COUNT_FORMATTERS) {
            throw PatchException("Show exact counts: ${liveFormatters.size} LIVE count formatters")
        }
        val scoreText = if (declaredBuild) SupporterScoreTextFingerprint.method else SupporterScoreTextFingerprint.methodOrNull
        val scoreField = coinFormatter?.let { scoreText?.supporterScoreField(it.definingClass) }
        if (declaredBuild && scoreField == null) {
            throw PatchException("Show exact counts: the supporters list's score text reads no score")
        }

        (formatters.map { it.method } + compactFormatters.map { it.method } + liveFormatters).forEach {
            it.hookCountFormatter()
        }
        if (scoreText != null && scoreField != null) scoreText.hookSupporterScoreText(scoreField)
    }
}

/** ExactCounts' answer first in a count formatter, and TikTok's text when it has none. */
internal fun MutableMethod.hookCountFormatter() {
    // p0 and p1 hold the long. v0 is a local the method writes before it reads.
    addInstructionsWithLabels(
        0,
        """
            invoke-static {p0, p1}, $EXACT_COUNTS_DESCRIPTOR->format(J)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :tiktok
            return-object v0
        """,
        ExternalLabel("tiktok", getInstruction(0)),
    )
}

/** The row's own score, whole, first in the supporters list's score text, and TikTok's text when there's none. */
internal fun MutableMethod.hookSupporterScoreText(score: FieldReference) {
    // p3 is the row, v20 on 48.0.2, past what iget-wide can name, so it's copied to v2 first.
    // v0 to v2 are locals the method writes before it reads.
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v2, p3
            iget-wide v0, v2, ${score.definingClass}->${score.name}:${score.type}
            invoke-static {v0, v1}, $EXACT_COUNTS_DESCRIPTOR->supporterScore(J)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :tiktok
            return-object v0
        """,
        ExternalLabel("tiktok", getInstruction(0)),
    )
}
