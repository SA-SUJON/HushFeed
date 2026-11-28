/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** TTNet's app provider: what it answers here is what the native network stack routes by. */
internal const val TTNET_ADAPTER = "Lcom/bytedance/ttnet/cronet/AbsCronetDependAdapter;"

/** The two TTNet interfaces the adapter implements itself, on TikTok and TikTok Lite alike. */
internal val TTNET_ADAPTER_INTERFACES = listOf(
    "Lcom/bytedance/frameworks/baselib/network/http/cronet/ICronetDepend;",
    "Lcom/bytedance/frameworks/baselib/network/http/cronet/ICronetAppProvider;",
)

/**
 * TTNet's app provider adapter. TikTok keeps its name. TikTok Lite (#502) renames it (`LX/21g;`
 * on 47.8.2), so there it's the one abstract class that implements both of TTNet's app
 * interfaces itself, as TikTok's does. Null when it's gone or more than one class fits.
 */
internal fun ttnetAdapter(classes: Sequence<ClassDef>, classOf: (String) -> ClassDef?): ClassDef? =
    classOf(TTNET_ADAPTER) ?: classes.filter {
        AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.INTERFACE.isSet(it.accessFlags) &&
            it.interfaces.containsAll(TTNET_ADAPTER_INTERFACES)
    }.toList().singleOrNull()

/** Chromium's telephony bridge inside TTNet, which native code calls for the carrier code. */
internal const val TTNET_NETWORK_LIBRARY = "Lcom/ttnet/org/chromium/net/AndroidNetworkLibrary;"

internal const val NETWORK_STACK_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/NetworkStackSim;"

private const val STRING = "Ljava/lang/String;"

/**
 * Each TTNet getter whose answer comes from the SIM, and the extension method that puts the
 * real SIM back into it. The system region is the phone's language setting, so it stays out.
 */
internal val NETWORK_STACK_BRIDGES = mapOf(
    "getCarrierRegion" to "region",
    "getRegion" to "region",
    "getAppInitialRegionInfo" to "initRegion",
    "getNetworkOperator" to "networkOperator",
    "getSimOperator" to "simOperator",
)

private val LIBRARY_GETTERS = setOf("getNetworkOperator", "getSimOperator")

internal class NetworkStackHook(val method: Method, val bridge: String)

private fun Method.isStringGetter() =
    parameterTypes.isEmpty() && returnType == STRING && implementation != null &&
        !AccessFlags.ABSTRACT.isSet(accessFlags)

private fun extendsAdapter(definition: ClassDef, adapter: String, classOf: (String) -> ClassDef?): Boolean {
    val seen = mutableSetOf<String>()
    var type = definition.superclass
    while (type != null && seen.add(type)) {
        if (type == adapter) return true
        type = classOf(type)?.superclass
    }
    return false
}

/**
 * #415: with SIM spoof on, a phone on one country's network told TikTok's network stack it was on
 * another's. TTNet reads the carrier region, the app region (the carrier region first), the
 * carrier code and the init-region header from these getters, sends them to its routing config
 * server and keeps the store region that comes back, so the image and API hosts it picked were the
 * preset country's. Every answer of every one of them, in the adapter and each class under it,
 * goes through the extension, which puts the real SIM back while the rest of the app keeps the
 * preset. Throws before anything is written when TTNet's shape moved.
 *
 * <p>TikTok Lite renames the adapter and leaves its region getters to the classes under it, so
 * the adapter itself only has to answer the two carrier codes. Every getter still has to be
 * answered somewhere in the adapter's tree.
 */
internal fun planNetworkStackHooks(classes: Sequence<ClassDef>, classOf: (String) -> ClassDef?): List<NetworkStackHook> {
    val adapter = ttnetAdapter(classes, classOf)
        ?: throw PatchException("SIM spoof: TTNet's app provider adapter is gone")
    val library = classOf(TTNET_NETWORK_LIBRARY)
        ?: throw PatchException("SIM spoof: TTNet's network library is gone")

    val owners = listOf(adapter) + classes.filter { it.type != adapter.type && extendsAdapter(it, adapter.type, classOf) }
    val providerHooks = owners.flatMap { owner ->
        owner.methods.filter {
            it.isStringGetter() && it.name in NETWORK_STACK_BRIDGES && !AccessFlags.STATIC.isSet(it.accessFlags)
        }.map { NetworkStackHook(it, NETWORK_STACK_BRIDGES.getValue(it.name)) }
    }
    val libraryHooks = library.methods.filter {
        it.isStringGetter() && it.name in LIBRARY_GETTERS && AccessFlags.STATIC.isSet(it.accessFlags)
    }.map { NetworkStackHook(it, NETWORK_STACK_BRIDGES.getValue(it.name)) }

    val adapterNames = providerHooks.filter { it.method.definingClass == adapter.type }.map { it.method.name }.toSet()
    val missing = (LIBRARY_GETTERS - adapterNames) + (NETWORK_STACK_BRIDGES.keys - providerHooks.map { it.method.name }.toSet())
    if (missing.isNotEmpty()) {
        throw PatchException("SIM spoof: TTNet's adapter no longer has ${missing.sorted()}")
    }
    // The adapter's own region getters answer "", or aren't there. The app's provider is what reads the SIM.
    for (name in listOf("getCarrierRegion", "getRegion", "getAppInitialRegionInfo")) {
        if (providerHooks.none { it.method.name == name && it.method.definingClass != adapter.type }) {
            throw PatchException("SIM spoof: no TTNet provider answers $name")
        }
    }
    if (libraryHooks.map { it.method.name }.toSet() != LIBRARY_GETTERS) {
        throw PatchException("SIM spoof: TTNet's network library no longer has $LIBRARY_GETTERS")
    }
    val hooks = providerHooks + libraryHooks
    hooks.firstOrNull { hook -> hook.method.implementation!!.instructions.none { it.opcode == Opcode.RETURN_OBJECT } }
        ?.let { throw PatchException("SIM spoof: ${it.method.definingClass}->${it.method.name} has no answer to hook") }
    return hooks
}

/** TikTok's feed response composer, on TikTok and TikTok Lite alike. */
internal const val FEED_COMPOSER = "Lcom/ss/android/ugc/aweme/app/api/proto/ComposePbAndJson;"

private const val MEDIA_BASIC_INFO = "Lcom/ss/android/ugc/aweme/feed/model/MediaBasicInfo;"
private const val FEED_IMAGE_ADDRESSES = "httpDnsResultList"
private const val LIST = "Ljava/util/List;"

private fun Instruction.readsFeedImageAddresses(): Boolean {
    if (opcode != Opcode.IGET_OBJECT) return false
    val field = (this as ReferenceInstruction).reference as? FieldReference ?: return false
    return field.definingClass == MEDIA_BASIC_INFO && field.name == FEED_IMAGE_ADDRESSES && field.type == LIST
}

internal class FeedAddressHook(val method: Method)

/**
 * #415, after the network stack went back to the real SIM: a feed response carries, for each
 * image host, server addresses TikTok's servers picked for a request that still carries the
 * preset, and the composer copies them into the table both image loaders connect by instead of
 * looking the host up. The one read of that list in the composer goes through the extension.
 * Throws before anything is written when the composer, the read, or a clean spot after it is gone.
 */
internal fun planFeedAddressHook(composer: ClassDef?): FeedAddressHook {
    composer ?: throw PatchException("SIM spoof: TikTok's feed composer is gone")
    val readers = composer.methods.filter { method ->
        method.implementation?.instructions?.any { it.readsFeedImageAddresses() } == true
    }
    val reader = readers.singleOrNull()
        ?: throw PatchException("SIM spoof: expected one feed composer method reading image server addresses, found ${readers.size}")
    val code = MutableMethodImplementation(reader.implementation!!).instructions
    val reads = code.indices.filter { code[it].readsFeedImageAddresses() }
    val read = reads.singleOrNull()
        ?: throw PatchException("SIM spoof: the feed composer reads its image server addresses ${reads.size} times, not once")
    val next = code.getOrNull(read + 1)
        ?: throw PatchException("SIM spoof: nothing follows the feed's image server addresses read")
    if (next.location.labels.isNotEmpty()) {
        throw PatchException("SIM spoof: something jumps to right after the feed's image server addresses are read")
    }
    return FeedAddressHook(reader)
}

/** Hands the feed's image server addresses to the extension, which may set them aside. */
internal fun MutableMethod.writeFeedAddressHook() {
    val read = implementation!!.instructions.indexOfFirst { it.readsFeedImageAddresses() }
    val register = getInstruction<TwoRegisterInstruction>(read).registerA
    addInstructions(
        read + 1,
        """
            invoke-static/range { v$register .. v$register }, $NETWORK_STACK_DESCRIPTOR->imageAddresses($LIST)$LIST
            move-result-object v$register
        """,
    )
}

/** Hands every answer of a TTNet getter to the extension, which may swap the real SIM back in. */
internal fun MutableMethod.writeNetworkStackHook(bridge: String) {
    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $NETWORK_STACK_DESCRIPTOR->$bridge($STRING)$STRING
                move-result-object v$register
            """,
        )
    }
}
