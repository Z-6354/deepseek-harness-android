package com.labteto.dshmobile.browser

import java.io.Closeable

/** Owns the atomic transition from a one-use read token to an active stream. */
internal class NativeReadRegistry<T>(private val disposeValue: (T) -> Unit) {
    internal class Ticket<T> internal constructor(
        val id: String,
        val partition: String,
        val key: String,
        val value: T,
        val expiresAt: Long,
        val document: Any,
        internal val slot: Any,
    )

    private class Slot<T>(
        val id: String,
        val partition: String,
        val key: String,
        val value: T,
        val expiresAt: Long,
        val document: Any,
        var consumed: Boolean = false,
        var stream: Closeable? = null,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Slot<T>>()

    fun issue(id: String, partition: String, key: String, value: T, expiresAt: Long, document: Any): Boolean = synchronized(lock) {
        if (entries.containsKey(id)) return@synchronized false
        entries[id] = Slot(id, partition, key, value, expiresAt, document)
        true
    }

    /** Removes the bearer token and installs an in-transit read under one lock. */
    fun consume(id: String): Ticket<T>? = synchronized(lock) {
        val slot = entries[id]?.takeIf { !it.consumed } ?: return@synchronized null
        slot.consumed = true
        Ticket(slot.id, slot.partition, slot.key, slot.value, slot.expiresAt, slot.document, slot)
    }

    fun register(ticket: Ticket<T>, stream: Closeable): Boolean {
        val registered = synchronized(lock) {
            val slot = entries[ticket.id]
            if (slot !== ticket.slot || slot.consumed.not() || slot.stream != null) false
            else { slot.stream = stream; true }
        }
        if (!registered) runCatching { stream.close() }
        return registered
    }

    fun isCurrent(ticket: Ticket<T>): Boolean = synchronized(lock) { entries[ticket.id] === ticket.slot }

    /** Serializes a bounded stream read against revoke, so revoke returns after in-flight reads finish. */
    fun <R> read(ticket: Ticket<T>, action: () -> R): R? = synchronized(lock) {
        val slot = entries[ticket.id]
        if (slot !== ticket.slot || slot?.stream == null) null else action()
    }

    /** The stream has already closed itself and released its value. */
    fun finish(ticket: Ticket<T>) = synchronized(lock) {
        if (entries[ticket.id] === ticket.slot) entries.remove(ticket.id)
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun expireTokens(now: Long, document: Any): Int = revokeWhere { !it.consumed && (it.expiresAt <= now || it.document !== document) }

    fun revoke(partition: String, key: String? = null): Int = revokeWhere { it.partition == partition && (key == null || it.key == key) }

    fun revoke(ticket: Ticket<T>): Boolean = revokeWhere { it === ticket.slot }.let { it != 0 }

    fun revokeAll(): Int = revokeWhere { true }

    private fun revokeWhere(predicate: (Slot<T>) -> Boolean): Int {
        val removed = synchronized(lock) {
            entries.values.filter(predicate).also { values -> values.forEach { entries.remove(it.id) } }
        }
        removed.forEach { slot ->
            val stream = slot.stream
            if (stream != null) runCatching { stream.close() } else runCatching { disposeValue(slot.value) }
        }
        return removed.size
    }
}
