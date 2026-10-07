package app.morphe.patches.tiktok.misc.spoof.region

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Region spoof's request fields rest on, held to each declared TikTok build.
 *
 * The patch hooks every return of AppLog's common-parameter builder and hands the extension the
 * map in p3. That needs the builder to be one instance method that never puts anything else in
 * that register. It also leaves carrier_region, sys_region and region alone, because the
 * network_common_params feature producer reads them from the region hub's static getters, which
 * the patch already wraps; current_region and residence come from SharePrefCache instead, which
 * is why the builder hook exists at all.
 */
class RegionSpoofAnchorsTest {
    @Test
    fun `the common-parameter builder is one instance method that only reads its map register`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val matches = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (CommonParamsBuilderFingerprint.takes(method, classDef)) matches += method
            }

            assertEquals(
                "$version: builders matched ${matches.map { "${it.definingClass}->${it.name}" }}",
                1, matches.size,
            )
            val builder = matches.single()
            assertFalse("$version: the builder is static", AccessFlags.STATIC.isSet(builder.accessFlags))
            val body = builder.implementation ?: error("$version: the builder has no body")
            val map = body.registerCount - 2
            val instructions = body.instructions.toList()
            assertTrue("$version: the builder writes its map register", instructions.none { writes(it, map) })
            assertTrue("$version: the builder has no return to hook", instructions.any { it.opcode == Opcode.RETURN_VOID })
            assertTrue("$version: the builder never puts into p3", instructions.any { puts(it, map) })
        }
    }

    @Test
    fun `the feature producer reads carrier_region sys_region and region from the wrapped hub`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var hub: String? = null
            val producers = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (classDef.type == REGION_SERVICE && method.name == "getRegion" && method.parameterTypes.isEmpty()) {
                    // The same reading the patch makes of it.
                    hub = method.implementation!!.instructions
                        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                        .single { it.returnType == STRING && it.parameterTypes.isEmpty() }
                        .definingClass
                }
                // A list of the same feature names sits elsewhere; the producer is the override
                // that answers one, and FeatureProducer keeps that method's name.
                if (method.name == "getFeatureInternal") {
                    val loaded = method.implementation?.instructions?.mapNotNull { string(it) }.orEmpty()
                    if ("f_global_carrier_region" in loaded && "f_global_sys_region" in loaded) producers += method
                }
            }

            val hubClass = hub
            assertNotNull("$version: RegionService.getRegion was not found", hubClass)
            assertEquals("$version: feature producers ${producers.map { it.definingClass }}", 1, producers.size)
            val instructions = producers.single().implementation!!.instructions.toList()
            val readers = mapOf(
                "f_global_carrier_region" to hubClass,
                "f_global_sys_region" to hubClass,
                "f_global_region" to hubClass,
                "f_global_current_region" to SHARE_PREF_CACHE,
                "f_global_residence" to SHARE_PREF_CACHE,
            )
            readers.forEach { (feature, owner) ->
                val at = instructions.indexOfFirst { string(it) == feature }
                assertTrue("$version: $feature is not in the producer", at >= 0)
                val read = instructions.drop(at + 1).firstOrNull { it.opcode == Opcode.INVOKE_STATIC }
                    ?.let { (it as ReferenceInstruction).reference as MethodReference }
                assertEquals("$version: what $feature is read from", owner, read?.definingClass)
                if (owner == hubClass) {
                    assertTrue(
                        "$version: $feature is read through ${read?.name}, not a static getter the patch wraps",
                        read!!.returnType == STRING && read.parameterTypes.isEmpty(),
                    )
                }
            }
        }
    }

    private fun forEachMethod(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                classDef.methods.forEach { method -> visit(classDef, method) }
            }
        }
    }

    private fun string(instruction: Instruction): String? =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun writes(instruction: Instruction, register: Int): Boolean {
        if (!instruction.opcode.setsRegister()) return false
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
        return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
    }

    private fun puts(instruction: Instruction, register: Int): Boolean {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return reference.definingClass == "Ljava/util/Map;" && reference.name == "put" &&
            (instruction as? FiveRegisterInstruction)?.registerC == register
    }

    private companion object {
        const val REGION_SERVICE = "Lcom/ss/android/ugc/aweme/app/services/RegionService;"
        const val SHARE_PREF_CACHE = "Lcom/ss/android/ugc/aweme/app/SharePrefCache;"
        const val STRING = "Ljava/lang/String;"
    }
}
