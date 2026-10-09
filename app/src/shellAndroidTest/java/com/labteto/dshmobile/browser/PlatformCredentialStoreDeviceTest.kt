package com.labteto.dshmobile.browser

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlatformCredentialStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withStore(test: (PlatformCredentialStore, android.content.SharedPreferences, String, String) -> Unit) {
        val suffix = UUID.randomUUID().toString()
        val prefsName = "credential_test_$suffix"
        val alias = "credential_test_$suffix"
        val store = PlatformCredentialStore(context, prefsName, alias)
        try { test(store, context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), prefsName, alias) }
        finally { assertTrue("Test encrypted data and key must be removed", store.clear()) }
    }

    @Test fun keystoreRoundTripIsCiphertextOnlyRandomizedAndSeparatedByExactOrigin() = withStore { store, prefs, name, alias ->
        val first = "https://one.test"
        val second = "https://two.test"
        val otherPort = "https://one.test:8443"
        val secret = "test password with unicode 密码"
        assertNull(store.read(first))
        assertTrue(store.save(first, secret))
        val record = prefs.getString(first, null)!!
        assertFalse(record.contains(secret))
        assertEquals(setOf(first), prefs.all.keys)
        assertEquals(secret, PlatformCredentialStore(context, name, alias).read(first))
        assertNull(store.read(second))
        assertNull(store.read(otherPort))
        assertTrue(store.save(first, secret))
        assertNotEquals("A second save must use a fresh IV", record, prefs.getString(first, null))
        assertTrue(store.save(second, "other server"))
        assertTrue(store.save(otherPort, "other port"))
        assertEquals(secret, store.read(first))
        assertEquals("other server", store.read(second))
        assertEquals("other port", store.read(otherPort))
        assertFalse(store.save("https://one.test/path", secret))
        assertFalse(store.save(first, "x".repeat(4097)))
    }

    @Test fun originAADAndCorruptedCiphertextAndMissingKeyFailClosed() = withStore { store, prefs, _, alias ->
        val first = "https://one.test"
        val second = "https://two.test"
        assertTrue(store.save(first, "private example"))
        val record = prefs.getString(first, null)!!
        assertTrue(prefs.edit().putString(second, record).commit())
        assertNull("Copying ciphertext must not cross origins", store.read(second))
        val parts = record.split('.')
        val bytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(prefs.edit().putString(first, parts[0] + "." + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit())
        assertNull("Authentication failure must not return a password", store.read(first))
        assertTrue(prefs.edit().putString(first, "malformed").commit())
        assertNull(store.read(first))
        assertTrue(prefs.edit().putString(first, record).commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertNull("A lost key must not be regenerated for reads", store.read(first))
    }

    @Test fun explicitClearRemovesEveryOriginAndKeyAndAllowsNewSave() = withStore { store, prefs, name, alias ->
        assertTrue(store.save("https://one.test", "first"))
        assertTrue(store.save("https://two.test", "second"))
        assertTrue(store.clear())
        assertTrue(prefs.all.isEmpty())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        val reopened = PlatformCredentialStore(context, name, alias)
        assertNull(reopened.read("https://one.test"))
        assertNull(reopened.read("https://two.test"))
        assertTrue(reopened.save("https://one.test", "after clear"))
        assertEquals("after clear", reopened.read("https://one.test"))
    }
}
