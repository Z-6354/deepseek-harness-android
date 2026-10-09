package com.labteto.dshmobile.update

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUpdateInstallerFileNameTest {
    private val hostile = listOf("..", ".", "...", "../evil.apk", "..\\..\\evil.apk", "/etc/passwd", "a/../../b", "", "   ", ".hidden")

    @Test fun `file name can never leave the updates directory`() {
        val dir = Files.createTempDirectory("updates").toFile()
        try {
            for (raw in hostile) {
                val name = AppUpdateInstaller.safeApkFileName(raw)
                assertTrue("'$raw' -> '$name' must be a plain leaf", name == File(name).name)
                assertFalse("'$raw' -> '$name'", name == "." || name == ".." || name.startsWith("."))
                assertTrue("'$raw' -> '$name'", name.endsWith(".apk"))
                assertEquals("'$raw' escaped", dir.canonicalPath, File(dir, name).canonicalFile.parentFile.canonicalPath)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `ordinary names are kept`() {
        assertEquals("dsha-0.12.12.apk", AppUpdateInstaller.safeApkFileName("dsha-0.12.12.apk"))
        assertEquals("dsha_1.apk", AppUpdateInstaller.safeApkFileName("dsha 1"))
    }
}
