package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class DocumentEpochTest {
    @Test fun initialSubresourceLeaseCanBeAdoptedByFirstStartedDocumentOnly() {
        val epoch = DocumentEpoch()
        epoch.beginBrowser()
        val beforeFirstStart = epoch.lease()
        assertTrue(epoch.ownsInitialConsumer(beforeFirstStart))
        epoch.documentStarted()
        assertFalse(epoch.owns(beforeFirstStart))
        assertTrue(epoch.ownsInitialConsumer(beforeFirstStart))
        epoch.onInternalCommit("https://example.test/")
        assertTrue(epoch.ownsInitialConsumer(beforeFirstStart))
        epoch.documentStarted()
        assertFalse(epoch.ownsInitialConsumer(beforeFirstStart))
    }

    @Test fun `document start bumps generation and clears commit`() {
        val doc = DocumentEpoch()
        doc.onInternalCommit("https://example.test/")
        assertFalse(doc.documentStarted())
        assertEquals(1L, doc.generation)
        assertNull(doc.committedInternalUrl)
        assertFalse(doc.sawInternalCommit)
    }

    @Test fun `goBack start does not clear the back flag for callers`() {
        val doc = DocumentEpoch()
        doc.onGoBack()
        assertTrue(doc.documentStarted())
        assertEquals(1L, doc.generation)
    }

    @Test fun `interactive before commit stays pending until commit`() {
        val doc = DocumentEpoch()
        doc.documentStarted()
        assertEquals(InteractiveDisposition.PendingCommit, doc.onInteractive(doc.generation))
        assertTrue(doc.pendingPageReady)
        assertTrue(doc.onInternalCommit("https://example.test/"))
        assertTrue(doc.consumePendingInteractive())
        assertFalse(doc.pendingPageReady)
    }

    @Test fun `interactive after commit is ready for visual`() {
        val doc = DocumentEpoch()
        doc.documentStarted()
        doc.onInternalCommit("https://example.test/")
        assertEquals(InteractiveDisposition.ReadyForVisual, doc.onInteractive(doc.generation))
    }

    @Test fun `stale generation is ignored`() {
        val doc = DocumentEpoch()
        doc.documentStarted()
        assertEquals(InteractiveDisposition.Ignored, doc.onInteractive(0L))
        assertFalse(doc.documentCommitted(0L, "https://example.test/"))
    }

    @Test fun `clearForNewLaunch keeps generation`() {
        val doc = DocumentEpoch()
        doc.documentStarted()
        doc.onInternalCommit("https://example.test/")
        doc.onInteractive(doc.generation)
        val generation = doc.generation
        doc.clearForNewLaunch()
        assertEquals(generation, doc.generation)
        assertNull(doc.committedInternalUrl)
        assertFalse(doc.pendingPageReady)
    }
    @Test fun `leases retire across document navigation browser replacement and closure`() {
        val doc = DocumentEpoch()
        doc.beginBrowser()
        val initial = doc.lease()
        assertTrue(doc.owns(initial))
        doc.documentStarted()
        assertFalse(doc.owns(initial))
        val current = doc.lease()
        assertTrue(doc.owns(current))
        doc.retireBrowser()
        assertFalse(doc.owns(current))
        doc.beginBrowser()
        assertEquals(current.documentGeneration, doc.lease().documentGeneration)
        assertFalse(doc.owns(current))
        assertNotEquals(current.browserInstance, doc.lease().browserInstance)
    }
}
