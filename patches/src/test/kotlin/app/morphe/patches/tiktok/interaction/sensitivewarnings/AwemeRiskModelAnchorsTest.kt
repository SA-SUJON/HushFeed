/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sensitivewarnings

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/**
 * Hide unverified content notices answers null from Aweme's risk model getter. Each declared
 * build has the getter as a plain field read, nothing outside it reads the field except the
 * routines that copy a video, and the guard lands in front of the read.
 */
class AwemeRiskModelAnchorsTest {
    @Test
    fun `each declared build reads the risk model only through its getter`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Method>()
            val readers = sortedSetOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef: ClassDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        val code = method.implementation ?: continue
                        if (AwemeRiskModelFingerprint.takes(method, classDef)) taken += method
                        if (code.instructions.any { instruction ->
                                instruction.opcode == Opcode.IGET_OBJECT &&
                                    instruction.getReference<FieldReference>()?.let {
                                        it.definingClass == AWEME && it.name == "awemeRiskModel"
                                    } == true
                            }) {
                            readers += "${classDef.type.substringAfterLast('/')}${method.name}"
                        }
                    }
                }
            }
            assertEquals("$version: ${taken.map { it.definingClass }}", 1, taken.size)
            val getter = taken.single()
            assertEquals(
                "$version: the getter",
                listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
                getter.implementation!!.instructions.map { it.opcode },
            )
            // Every reader past the getter copies a video: a clone, a merge, or the proto converter
            // that fills an Aweme from the server's struct.
            val copies = setOf("Aweme;getAwemeRiskModel", "Aweme;clone", "AwemeUpdateExtKt;update")
            assertTrue(
                "$version: field readers $readers",
                readers.all { it in copies || it.startsWith("ConvertHelper;") },
            )
            assertTrue("$version: getter among readers", "Aweme;getAwemeRiskModel" in readers)

            val patched = MutableMethod(getter)
            patched.guardAtEntry(
                "Skip content warnings",
                "invoke-static {}, Lapp/morphe/extension/tiktok/feed/SensitiveWarnings;->hideUnverifiedNotices()Z",
                "const/4 v0, 0x0\nreturn-object v0",
            )
            val after = patched.implementation!!.instructions.toList()
            assertEquals(
                "$version: the guard",
                listOf(
                    Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT,
                    Opcode.NOP, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT,
                ),
                after.map { it.opcode },
            )
            assertEquals("hideUnverifiedNotices", after[0].getReference<MethodReference>()!!.name)
        }
    }
}
