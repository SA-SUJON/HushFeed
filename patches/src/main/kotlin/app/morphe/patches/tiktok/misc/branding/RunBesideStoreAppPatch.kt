/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/BesideStoreApp;"
private const val JSON = "Lorg/json/JSONObject;"

internal const val JSON_PUT = "$JSON->put(Ljava/lang/String;Ljava/lang/Object;)$JSON"
internal const val GET_PACKAGE_NAME = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"

/** The registration header field TikTok's servers read the app's identity from. */
internal const val PACKAGE_KEY = "package"

/** How far before the write its key's `const-string` may sit (two on every declared build). */
private const val KEY_LOOKBACK = 3

/** How far before the write the running package may be read (seven on every declared build). */
private const val VALUE_LOOKBACK = 10

/**
 * AppLog's package header loader, the bd_tracker PackageLoader that fills the header every
 * `device_register` request and log upload carries: `package` (the running package, or a
 * configured override TikTok never sets), `real_package_name` beside an override, then
 * `app_version`, `app_version_minor`, `version_code`, `update_version_code` and
 * `manifest_version_code`. X.07Yj.LIZ on 47.0.3, X.08Aj.LIZ on 47.1.3 and X.08An.LIZ on 47.1.4,
 * the only `(JSONObject)Z` loading all three strings below on each build.
 */
internal object RegistrationPackageHeaderFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(JSON),
    strings = listOf("real_package_name", "app_version_minor", "manifest_version_code"),
)

/**
 * Where [method] writes the running package into the `package` field: the index of each
 * `JSONObject.put(String, Object)` on three registers whose key register was last set by a
 * `const-string` of [PACKAGE_KEY] just before, and whose value register was last set by the
 * `move-result-object` of a `Context.getPackageName()` a few instructions before. The write of
 * TikTok's configured override, also under `package`, takes its value from a field and isn't
 * counted, so a reshaped loader leaves the patch without its site and it stops.
 */
internal fun registrationPackageWrites(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || call.getReference<MethodReference>()?.toString() != JSON_PUT) {
            return@filter false
        }
        val invoke = call as? FiveRegisterInstruction ?: return@filter false
        if (invoke.registerCount != 3) return@filter false

        val keyLoad = lastWriteOf(instructions, invoke.registerD, index, KEY_LOOKBACK)?.let { instructions[it] }
        val loadsKey = (keyLoad?.opcode == Opcode.CONST_STRING || keyLoad?.opcode == Opcode.CONST_STRING_JUMBO) &&
            keyLoad?.getReference<StringReference>()?.string == PACKAGE_KEY

        val valueStored = lastWriteOf(instructions, invoke.registerE, index, VALUE_LOOKBACK)
        val readsRunningPackage = valueStored != null && valueStored > 0 &&
            instructions[valueStored].opcode == Opcode.MOVE_RESULT_OBJECT &&
            instructions[valueStored - 1].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[valueStored - 1].getReference<MethodReference>()?.toString() == GET_PACKAGE_NAME

        loadsKey && readsRunningPackage
    }
}

/** The index of the last instruction within [lookback] before [before] that writes [register]. */
private fun lastWriteOf(instructions: List<Instruction>, register: Int, before: Int, lookback: Int): Int? =
    (before - 1 downTo maxOf(0, before - lookback)).firstOrNull {
        instructions[it].opcode.setsRegister() && (instructions[it] as? OneRegisterInstruction)?.registerA == register
    }

@Suppress("unused")
val runBesideStoreAppPatch = bytecodePatch(
    name = "Run beside the store app",
    description = "Lets a TikTok copy renamed with Morphe's Clone app patch sign in while the store app stays " +
        "installed. Select it together with Clone app. When the copy registers your phone with TikTok it gives " +
        "TikTok's own package name, because TikTok's servers don't hand out a device ID for a name they don't " +
        "know and signing in fails without one. A build that keeps TikTok's package name is left alone. Google " +
        "and Facebook sign-in can't work in a renamed copy, so log in with your email or phone number.",
    default = false,
) {
    category("Settings")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val loader = RegistrationPackageHeaderFingerprint.method
        val writes = registrationPackageWrites(loader)
        if (writes.size != 1) {
            throw PatchException(
                "Run beside the store app: ${loader.definingClass}->${loader.name} writes the running " +
                    "package into the registration header ${writes.size} times, not once.",
            )
        }
        val index = writes.single()
        val put = loader.implementation!!.instructions.elementAt(index) as FiveRegisterInstruction
        if (maxOf(put.registerC, put.registerD, put.registerE) > 15) {
            throw PatchException("Run beside the store app: the header write's registers don't fit a static call.")
        }
        // Replaced in place with a static call on the same three registers: the header, the key
        // and the running package. Its result goes unread, as the original's did.
        loader.replaceInstruction(
            index,
            "invoke-static { v${put.registerC}, v${put.registerD}, v${put.registerE} }, " +
                "$EXTENSION->putPackage(${JSON}Ljava/lang/String;Ljava/lang/Object;)$JSON",
        )
    }
}
