/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/DevicePrivacyGuard;"
private const val CLIPBOARD = "Landroid/content/ClipboardManager;"
private const val NETWORK_CAPABILITIES = "Landroid/net/NetworkCapabilities;"
private const val NETWORK_INTERFACE = "Ljava/net/NetworkInterface;"
private const val AD_ID_INFO = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;"

@Suppress("unused")
val devicePrivacyGuardPatch = bytecodePatch(
    name = "Device privacy guard",
    description = "Stops TikTok reading a few things about your device. Blocking what you copied is on by default, and copying a link from TikTok still works. Hiding a VPN connection and handing it a blank advertising id are each a switch you turn on. All of them sit under Hushfeed settings > Privacy.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDevicePrivacyGuard()V",
        )

        // One replacement per signature. A single intercept returning ClipData for all three
        // put a ClipData where getText's CharSequence and hasPrimaryClip's boolean were
        // expected, which the verifier rejects as soon as the class loads.
        val replacements = mapOf(
            "$CLIPBOARD->getPrimaryClip()Landroid/content/ClipData;" to
                "$EXTENSION->interceptPrimaryClip($CLIPBOARD)Landroid/content/ClipData;",
            "$CLIPBOARD->getText()Ljava/lang/CharSequence;" to
                "$EXTENSION->interceptClipboardText($CLIPBOARD)Ljava/lang/CharSequence;",
            "$CLIPBOARD->hasPrimaryClip()Z" to
                "$EXTENSION->interceptHasPrimaryClip($CLIPBOARD)Z",
        )
        val sites = invokeSitesOf(replacements.keys)
        if (sites.isEmpty()) {
            throw PatchException("Device privacy guard: no clipboard read call site was found.")
        }
        replaceSites(sites, replacements)
        println("[Device privacy guard] Intercepted ${sites.size} clipboard read sites.")

        // VPN and advertising id, both virtual calls. hasTransport is answered false only for the
        // VPN transport, every other transport untouched; the Info.getId read is answered with the
        // blank id. The site hands the Info in as an Object, since the extension doesn't compile
        // against Play Services. One replacement per signature, each returning the type read back.
        val vpnAndAdId = mapOf(
            "$NETWORK_CAPABILITIES->hasTransport(I)Z" to
                "$EXTENSION->interceptHasTransport(${NETWORK_CAPABILITIES}I)Z",
            "$AD_ID_INFO->getId()Ljava/lang/String;" to
                "$EXTENSION->interceptAdvertisingId(Ljava/lang/Object;)Ljava/lang/String;",
        )
        val vpnAdSites = invokeSitesOf(vpnAndAdId.keys)
        if (vpnAdSites.none { it.target.startsWith(NETWORK_CAPABILITIES) }) {
            throw PatchException("Device privacy guard: no NetworkCapabilities.hasTransport call site was found.")
        }
        if (vpnAdSites.none { it.target.startsWith(AD_ID_INFO) }) {
            throw PatchException("Device privacy guard: no advertising id read call site was found.")
        }
        replaceSites(vpnAdSites, vpnAndAdId)

        // getNetworkInterfaces is static, so its sites are read on their own and the replacement
        // takes no receiver. It drops the tunnel interfaces a VPN adds.
        val interfaces = mapOf(
            "$NETWORK_INTERFACE->getNetworkInterfaces()Ljava/util/Enumeration;" to
                "$EXTENSION->interceptNetworkInterfaces()Ljava/util/Enumeration;",
        )
        val interfaceSites = invokeSitesOf(interfaces.keys, static = true)
        if (interfaceSites.isEmpty()) {
            throw PatchException("Device privacy guard: no NetworkInterface.getNetworkInterfaces call site was found.")
        }
        replaceSites(interfaceSites, interfaces)
        println(
            "[Device privacy guard] Intercepted ${vpnAdSites.size} VPN and advertising-id sites " +
                "and ${interfaceSites.size} network-interface sites.",
        )
    }
}
