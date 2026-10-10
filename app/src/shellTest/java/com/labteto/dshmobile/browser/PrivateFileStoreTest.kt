package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivateFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val label = "a".repeat(64)
    private val key = "b".repeat(64)

    @Test fun writeRestartLookupAndSnapshotPinSurviveSameKeyReplacement() {
        val root = temp.newFolder("private")
        var store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, "old".toByteArray())
        val pinned = store.openSnapshot(partition, key)!!

        publish(store, partition, key, "new".toByteArray())
        assertEquals("old", store.open(pinned) { true }!!.bufferedReader().use { it.readText() })
        store.release(pinned)

        store = PrivateFileStore(root, "owner-A")
        assertEquals(3L, store.lookup(partition, key)!!.bytes)
        val latest = store.openSnapshot(partition, key)!!
        assertArrayEquals("new".toByteArray(), store.open(latest) { true }!!.use { it.readBytes() })
    }

    @Test fun cleanupFenceBlocksLateWriteAndOnlyCompleteCleanupReopensStore() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val write = store.beginWrite(partition, key, 3, "application/octet-stream")!!
        assertEquals(3L, store.append(write, 0, byteArrayOf(1, 2, 3)))
        store.beginCleanup()
        assertNull(store.commit(write))
        assertFalse(store.isAvailable())
        assertTrue(store.completeCleanup())
        assertTrue(store.isAvailable())
        assertNull(store.lookup(partition, key))
    }

    @Test fun corruptFenceFailsClosedUntilCleanupRepairsIt() {
        val root = temp.newFolder("private")
        PrivateFileStore(root, "owner-A")
        File(root, "fence.properties").writeText("broken")
        val reopened = PrivateFileStore(root, "owner-A")
        assertFalse(reopened.isAvailable())
        reopened.beginCleanup()
        assertTrue(reopened.completeCleanup())
        assertTrue(reopened.isAvailable())
    }

    @Test fun corruptFenceIsRebuiltWithAFreshEpochAndEmptyData() {
        val root = temp.newFolder("private")
        var store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, "old".toByteArray())
        val oldEpoch = store.currentEpoch()
        File(root, "fence.properties").writeText("broken")
        store = PrivateFileStore(root, "owner-A")
        assertFalse(store.isAvailable())
        assertTrue(store.repairCorruptFence())
        assertTrue(store.isAvailable())
        assertNotEquals(oldEpoch, store.currentEpoch())
        assertNull("the cache was emptied", store.lookup(store.partitionId(PrivateFileStore.NAMESPACE, label)!!, key))
        assertFalse("a healthy store is never repaired again", store.repairCorruptFence())
    }

    @Test fun forgedInvalidEpochIsAlsoRepaired() {
        val root = temp.newFolder("private")
        PrivateFileStore(root, "owner-A")
        File(root, "fence.properties").writeText("epoch=invalid\nblocked=false\n")
        val store = PrivateFileStore(root, "owner-A")
        assertFalse(store.isAvailable())
        assertTrue(store.repairCorruptFence())
        assertTrue(store.isAvailable())
    }

    @Test fun repairNeverTouchesARunningCleanupFence() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A")
        store.beginCleanup("nonce-1")
        assertFalse(store.repairCorruptFence())
        assertFalse(store.isAvailable())
        assertTrue(store.deleteForCleanup("nonce-1") && store.finishCleanup("nonce-1"))
    }

    @Test fun invalidPartitionAndEntryLimitsAreRejected() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner-A")
        assertNull(store.partitionId("unknown", label))
        assertNull(store.partitionId(PrivateFileStore.NAMESPACE, "bad"))
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        assertNull(store.beginWrite(partition, "../", 3, "application/octet-stream"))
        assertNull(store.beginWrite(partition, key, PrivateFileStore.MAX_ENTRY_BYTES + 1, "application/octet-stream"))
        assertNull(store.beginWrite(partition, key, 3, "text/html"))
    }

    @Test fun sequenceTracksFramesRatherThanChunkLength() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val write = store.beginWrite(partition, key, 6, "application/octet-stream")!!
        assertEquals(2L, store.append(write, 0, byteArrayOf(1, 2)))
        assertNull(store.append(write, 2, byteArrayOf(3, 4)))
        assertEquals(4L, store.append(write, 1, byteArrayOf(3, 4)))
        assertEquals(6L, store.append(write, 2, byteArrayOf(5, 6)))
        assertEquals(6L, store.commit(write)!!.bytes)
    }

    @Test fun quotaEvictsLeastRecentlyUsedButNeverPinnedSnapshot() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner-A", PrivateFileStore.Limits(8, 8, 8, 2))
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val firstKey = "c".repeat(64)
        val secondKey = "d".repeat(64)
        publish(store, partition, firstKey, ByteArray(5) { 1 })
        val pinned = store.openSnapshot(partition, firstKey)!!
        assertNull(store.beginWrite(partition, secondKey, 4, "application/octet-stream"))
        store.release(pinned)
        publish(store, partition, secondKey, ByteArray(4) { 2 })
        assertNull(store.lookup(partition, firstKey))
        assertEquals(4L, store.lookup(partition, secondKey)!!.bytes)
    }

    @Test fun quotaOrIoFailureNeverEvictsTheOldValueBeforeOverwritePublishes() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A", PrivateFileStore.Limits(8, 8, 8, 4))
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, ByteArray(5) { 7 })
        assertNull(store.beginWrite(partition, key, 4, "application/octet-stream"))
        assertEquals(5L, store.lookup(partition, key)!!.bytes)
        val write = store.beginWrite(partition, key, 3, "application/octet-stream")!!
        assertTrue(write.temp.delete())
        assertNull(store.append(write, 0, byteArrayOf(1, 2, 3)))
        store.abort(write)
        assertEquals(5L, store.lookup(partition, key)!!.bytes)
    }

    @Test fun corruptPublishedBodyBecomesMissAfterRestart() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, byteArrayOf(1, 2, 3))
        val body = File(root, "data/$partition").listFiles()!!.single { it.extension == "bin" }
        body.writeBytes(byteArrayOf(3, 2, 1))
        val restarted = PrivateFileStore(root, "owner-A")
        assertNull(restarted.lookup(partition, key))
    }

    @Test fun missingTemporaryFileFailsWriteWithoutPublishing() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val write = store.beginWrite(partition, key, 3, "application/octet-stream")!!
        assertTrue(write.temp.delete())
        assertNull(store.append(write, 0, byteArrayOf(1, 2, 3)))
        assertNull(store.commit(write))
        assertNull(store.lookup(partition, key))
    }

    @Test fun separateStoreInstancesShareTheCleanupFenceAndCannotPublishLate() {
        val root = temp.newFolder("private")
        val writerStore = PrivateFileStore(root, "owner-A")
        val cleanupStore = PrivateFileStore(root, "owner-B")
        val partition = writerStore.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val write = writerStore.beginWrite(partition, key, 3, "application/octet-stream")!!
        assertEquals(3L, writerStore.append(write, 0, byteArrayOf(1, 2, 3)))
        cleanupStore.beginCleanup()
        assertNull(writerStore.commit(write))
        assertTrue(cleanupStore.deleteForCleanup())
        assertFalse(writerStore.isAvailable())
        assertTrue(cleanupStore.finishCleanup())
        assertNull(writerStore.lookup(partition, key))
    }

    @Test fun removeRevokesWriterThatStartedBeforeTheRemoval() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val write = store.beginWrite(partition, key, 3, "application/octet-stream")!!
        assertEquals(3L, store.append(write, 0, byteArrayOf(1, 2, 3)))

        assertTrue(store.remove(partition, key))

        assertNull(store.commit(write))
        assertNull(store.lookup(partition, key))
    }

    @Test fun recoveryRemovesUnreferencedBlobsButPreservesActiveWritesAndPinnedSnapshots() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, "old".toByteArray())
        val snapshot = store.openSnapshot(partition, key)!!
        publish(store, partition, key, "new".toByteArray())
        val pinnedPath = snapshot.file
        val orphan = File(root, "data/$partition/${"c".repeat(64)}.${"d".repeat(32)}.bin")
        orphan.writeBytes(byteArrayOf(4, 5, 6))
        val active = store.beginWrite(partition, "e".repeat(64), 3, "application/octet-stream")!!

        store.recoverOrphanWrites()
        assertFalse(orphan.exists())
        assertTrue("in-process write temp survives recovery", active.temp.isFile)
        assertTrue("pinned immutable revision survives recovery", pinnedPath.isFile)

        store.release(snapshot)
        store.recoverOrphanWrites()
        assertFalse("released, unreferenced revision is reclaimed", pinnedPath.exists())
        store.abort(active)
    }

    @Test fun recoveryReclaimsPreviouslyPinnedRevisionAfterProcessDeath() {
        val root = temp.newFolder("private")
        val store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, "old".toByteArray())
        val snapshot = store.openSnapshot(partition, key)!!
        publish(store, partition, key, "new".toByteArray())
        assertTrue(snapshot.file.isFile)

        PrivateFileStore.resetSharedStateForProcessRestart(root)
        val restarted = PrivateFileStore(root, "owner-A")
        restarted.recoverOrphanWrites()

        assertFalse("dead process pin no longer retains an unreachable blob", snapshot.file.exists())
        assertEquals(3L, restarted.lookup(partition, key)!!.bytes)
    }

    @Test fun recoveryAfterPersistedCleanupTransactionRepairsBothCrashWindows() {
        val root = temp.newFolder("private")
        var store = PrivateFileStore(root, "owner-A")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        publish(store, partition, key, "secret".toByteArray())
        val transaction = CleanupTransaction("nonce-before-fence", "owner-B", "epoch-A")

        // Crash after the preferences transaction was committed but before its storage fence.
        var recovery = PrivateCleanupFence(store)
        assertTrue(recovery.prepare(transaction))
        assertNull(store.lookup(partition, key))
        assertFalse(store.isAvailable())
        assertFalse("a stale transaction cannot open the durable fence", store.finishCleanup("stale-nonce"))
        assertTrue(recovery.finish(transaction))

        // Crash after the storage fence was finished but before preferences cleared pending.
        val epochBeforeRetry = store.currentEpoch()
        store = PrivateFileStore(root, "owner-A")
        recovery = PrivateCleanupFence(store)
        assertTrue(recovery.prepare(transaction))
        assertNotEquals("recovery fences off the post-finish/preference window", epochBeforeRetry, store.currentEpoch())
        assertTrue(recovery.finish(transaction))
        assertTrue(store.isAvailable())
        assertNull(store.lookup(partition, key))
    }

    private fun publish(store: PrivateFileStore, partition: String, key: String, data: ByteArray) {
        val write = store.beginWrite(partition, key, data.size.toLong(), "application/octet-stream")!!
        assertEquals(data.size.toLong(), store.append(write, 0, data))
        assertEquals(data.size.toLong(), store.commit(write)!!.bytes)
    }
}
