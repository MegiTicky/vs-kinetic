package org.valkyrienskies.vskinetic.collision

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded handoff between VS physics callbacks and the server tick.
 * This class deliberately stores no Minecraft world references.
 */
object ImpactQueue {
    private const val MAX_PENDING = 4096
    private val pending = ConcurrentLinkedQueue<ImpactRecord>()
    private val captured = AtomicLong()
    private val dropped = AtomicLong()

    fun offer(record: ImpactRecord): Boolean {
        if (pending.size >= MAX_PENDING) {
            dropped.incrementAndGet()
            return false
        }
        pending.add(record)
        captured.incrementAndGet()
        return true
    }

    fun drain(maxRecords: Int = 1024): List<ImpactRecord> {
        val result = ArrayList<ImpactRecord>(maxRecords)
        while (result.size < maxRecords) {
            val record = pending.poll() ?: break
            result.add(record)
        }
        return result
    }

    fun pendingCount(): Int = pending.size
    fun capturedCount(): Long = captured.get()
    fun droppedCount(): Long = dropped.get()
}
