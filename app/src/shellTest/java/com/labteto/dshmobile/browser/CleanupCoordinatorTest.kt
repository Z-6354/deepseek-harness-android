package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class CleanupCoordinatorTest {
    private val first = CleanupTransaction("first", "owner-b", "epoch")
    private val second = CleanupTransaction("second", "owner-c", "epoch")

    @Test fun `recreation delivers delayed completion to latest observer only`() {
        val coordinator = CleanupCoordinator()
        lateinit var done: () -> Unit
        val deliveries = mutableListOf<String>()
        var deletes = 0
        coordinator.attachOrStart(first, "retired-activity", { deletes++; done = it }, { true }) { _, _ -> deliveries += "retired" }
        coordinator.detach("retired-activity")
        coordinator.attachOrStart(first, "current-activity", { deletes++; done = it }, { it == first }) { transaction, success ->
            assertEquals(first, transaction); assertTrue(success); deliveries += "current"
        }
        assertTrue(coordinator.isCleaning)
        assertTrue(runCatching { coordinator.assertCanCreateWriter() }.isFailure)
        done()
        assertEquals(1, deletes)
        assertEquals(listOf("current"), deliveries)
        coordinator.assertCanCreateWriter()
    }

    @Test fun `retarget and local logout cannot clear newer pending transaction`() {
        for (replacement in listOf(second, second.copy(targetOwner = null))) {
            val coordinator = CleanupCoordinator()
            var pending: CleanupTransaction? = first
            lateinit var done: () -> Unit
            var opened = false
            coordinator.attachOrStart(first, "activity", { done = it }, { captured ->
                if (captured == pending) { pending = null; true } else false
            }) { _, success -> opened = success }
            pending = replacement
            assertFalse(coordinator.attachOrStart(replacement, "new", { error("Must not start overlapping deletion") }, { true }) { _, _ -> error("Must not deliver old result to a new target") })
            done()
            assertEquals(replacement, pending)
            assertFalse(opened)
            assertFalse(coordinator.isCleaning)
        }
    }

    @Test fun `destroyed observer cannot create view and duplicate completion cannot affect next cleanup`() {
        val coordinator = CleanupCoordinator()
        lateinit var oldDone: () -> Unit
        lateinit var newDone: () -> Unit
        val commits = mutableListOf<String>()
        coordinator.attachOrStart(first, "gone", { oldDone = it }, { commits += it.nonce; true }) { _, _ -> error("Destroyed Activity callback") }
        coordinator.detach("gone")
        oldDone()
        coordinator.attachOrStart(second, "live", { newDone = it }, { commits += it.nonce; true }) { _, success -> assertTrue(success) }
        oldDone()
        assertEquals(listOf("first"), commits)
        assertTrue(coordinator.isCleaning)
        newDone()
        assertEquals(listOf("first", "second"), commits)
    }
}
