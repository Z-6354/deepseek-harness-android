package com.labteto.dshmobile.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GitHubAppUpdateSourceTest {
    @Test fun `parses latest release apk and checksum`() = runBlocking {
        MockWebServer().use { server ->
            val apkUrl = server.url("app-release.apk").toString()
            val sumsUrl = server.url("SHA256SUMS.txt").toString()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "tag_name": "v0.13.0",
                      "draft": false,
                      "prerelease": false,
                      "body": "fixes",
                      "assets": [
                        {"name":"app-release.apk","size":2048,"browser_download_url":"$apkUrl"},
                        {"name":"SHA256SUMS.txt","browser_download_url":"$sumsUrl"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  app-release.apk\n",
                ),
            )
            val base = server.url("/").toString().trimEnd('/')
            val offer = GitHubAppUpdateSource(
                owner = "o",
                repo = "r",
                client = server.newClient(),
                apiBase = base,
            ).latest()
            assertNotNull(offer)
            assertEquals("0.13.0", offer!!.versionName)
            assertEquals(1_300, offer.versionCode)
            assertEquals("app-release.apk", offer.apkName)
            assertEquals(2048L, offer.apkBytes)
            assertEquals(apkUrl, offer.apkUrl)
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", offer.sha256)
            assertEquals("fixes", offer.releaseNotes)
        }
    }

    @Test fun `skips prerelease`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.0.0","prerelease":true,"assets":[]}"""))
            val base = server.url("/").toString().trimEnd('/')
            val offer = GitHubAppUpdateSource("o", "r", server.newClient(), base).latest()
            assertNull(offer)
        }
    }
}

private fun MockWebServer.newClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()
