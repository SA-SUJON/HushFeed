package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Device privacy guard" hides from TikTok, held to each declared build.
 *
 * The VPN and advertising-id switches rest on three framework reads: the modern VPN check
 * `NetworkCapabilities.hasTransport`, the advertising id read `AdvertisingIdClient$Info.getId`,
 * and the static interface walk `NetworkInterface.getNetworkInterfaces`. The patch swaps every
 * call site of each for its own, so this pins that each build still makes the call and in the
 * invoke form the replacement is emitted in. A build that stops making one fails here, loudly,
 * rather than shipping a switch that does nothing.
 */
class DevicePrivacyGuardAnchorsTest {
    private val networkCapabilities = "Landroid/net/NetworkCapabilities;"
    private val networkInterface = "Ljava/net/NetworkInterface;"
    private val adIdInfo = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;"

    private val virtual = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
    private val static = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

    @Test
    fun `each declared build makes the VPN and advertising-id reads the guard answers`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var hasTransport = 0
            var getId = 0
            var getInterfaces = 0
            val wrongForm = mutableListOf<String>()

            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    // The real app's own code, not an extension payload (fixtures carry none).
                    if (classDef.type.startsWith("Lapp/morphe/extension/")) return@forEach
                    classDef.methods.forEach { method ->
                        method.implementation?.instructions?.forEach { instruction ->
                            val ref = reference(instruction) ?: return@forEach
                            when {
                                ref.definingClass == networkCapabilities && ref.name == "hasTransport" &&
                                    ref.returnType == "Z" -> {
                                    hasTransport++
                                    if (instruction.opcode !in virtual) wrongForm += "hasTransport ${instruction.opcode}"
                                }
                                ref.definingClass == adIdInfo && ref.name == "getId" &&
                                    ref.returnType == "Ljava/lang/String;" -> {
                                    getId++
                                    if (instruction.opcode !in virtual) wrongForm += "getId ${instruction.opcode}"
                                }
                                ref.definingClass == networkInterface && ref.name == "getNetworkInterfaces" -> {
                                    getInterfaces++
                                    if (instruction.opcode !in static) wrongForm += "getNetworkInterfaces ${instruction.opcode}"
                                }
                            }
                        }
                    }
                }
            }

            assertTrue("$version: no NetworkCapabilities.hasTransport call site", hasTransport > 0)
            assertTrue("$version: no AdvertisingIdClient\$Info.getId call site", getId > 0)
            assertTrue("$version: no NetworkInterface.getNetworkInterfaces call site", getInterfaces > 0)
            assertTrue("$version: a read is in an invoke form the replacement can't match: $wrongForm",
                wrongForm.isEmpty())
        }
    }

    private fun reference(instruction: Instruction): MethodReference? =
        (instruction as? ReferenceInstruction)?.reference as? MethodReference
}
