package com.labteto.dshmobile.update

import org.junit.Assert.*
import org.junit.Test

class HostedAppUpdateSourceTest {
    @Test fun `parses hosted manifest`() {
        val offer = HostedAppUpdateSource.parseManifest(
            """
            {
              "versionName": "0.16.1",
              "versionCode": 1601,
              "apkUrl": "https://dsh.wannian.fun/dsha/update/dsha-0.16.1.apk",
              "apkName": "dsha-0.16.1.apk",
              "apkBytes": 4096,
              "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "releaseNotes": "hosted"
            }
            """.trimIndent(),
        )
        assertNotNull(offer)
        assertEquals("0.16.1", offer!!.versionName)
        assertEquals(1_601, offer.versionCode)
        assertEquals("dsha-0.16.1.apk", offer.apkName)
        assertEquals("hosted", offer.releaseNotes)
    }

    @Test fun `rejects non-https apk url`() {
        assertNull(
            HostedAppUpdateSource.parseManifest(
                """{"versionName":"1.0.0","apkUrl":"http://evil.example/a.apk"}""",
            ),
        )
    }
}
