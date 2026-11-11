package com.jarves.mh.runtime

import com.jarves.mh.runtime.installer.wipeHostRuntimePreservingVar
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatibilityTest {
    @Test
    fun acceptsNativeArm64Android() {
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a", "armeabi-v7a"), "aarch64"))
    }

    @Test
    fun acceptsArm64DevicesReportingVendorOsArch() {
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"), "armv8l"))
        assertTrue(supportsArm64Runtime(arrayOf("arm64-v8a"), null))
    }

    @Test
    fun rejectsX8664EvenWhenTranslationAdvertisesArm64() {
        assertFalse(supportsArm64Runtime(arrayOf("arm64-v8a", "x86_64"), "x86_64"))
    }

    @Test
    fun rejectsDevicesWithoutArm64Abi() {
        assertFalse(supportsArm64Runtime(arrayOf("x86_64", "x86"), "x86_64"))
        assertFalse(supportsArm64Runtime(arrayOf("armeabi-v7a"), "armv7l"))
    }

    @Test
    fun onlyOffersDeepSeekHarnessVersionValidatedForThisBuild() {
        assertTrue(isSupportedDshVersion(RuntimeInstaller.DSH_VERSION))
        assertFalse(isSupportedDshVersion("0.2.0-rc.2"))
        assertFalse(isSupportedDshVersion(""))
    }

    @Test
    fun wipeHostRuntimePreservesVarContainersWhileRemovingAllOtherTopLevelEntries() {
        val tempRoot = Files.createTempDirectory("mhr-wipe-test").toFile()
        try {
            val usrDir = tempRoot.resolve("usr").apply { mkdirs() }
            usrDir.resolve(".mhr-version").writeText("2026.11.10")
            usrDir.resolve("bin/proot").apply { parentFile!!.mkdirs(); writeText("old-proot") }
            usrDir.resolve("lib/libpython3.14.so").apply { parentFile!!.mkdirs(); writeText("old-py") }
            usrDir.resolve("libexec/proot/loader").apply { parentFile!!.mkdirs(); writeText("old-loader") }
            usrDir.resolve("etc/tls/cert.pem").apply { parentFile!!.mkdirs(); writeText("old-cert") }
            usrDir.resolve("tmp/stale.tmp").apply { parentFile!!.mkdirs(); writeText("temp") }

            val ubuntuMarker = usrDir.resolve("var/lib/proot-distro/containers/ubuntu/rootfs/root/keep.txt").apply {
                parentFile!!.mkdirs()
                writeText("preserved-container-data")
            }

            wipeHostRuntimePreservingVar(usrDir)

            assertFalse(usrDir.resolve(".mhr-version").exists())
            assertFalse(usrDir.resolve("bin").exists())
            assertFalse(usrDir.resolve("lib").exists())
            assertFalse(usrDir.resolve("libexec").exists())
            assertFalse(usrDir.resolve("etc").exists())
            assertFalse(usrDir.resolve("tmp").exists())
            assertTrue(ubuntuMarker.isFile)
            assertEquals("preserved-container-data", ubuntuMarker.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
