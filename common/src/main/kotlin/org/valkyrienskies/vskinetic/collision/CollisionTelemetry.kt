package org.valkyrienskies.vskinetic.collision

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TelemetrySnapshot(
    val captured: Long,
    val dropped: Long,
    val processed: Long,
    val lastImpact: ImpactRecord?
)

object CollisionTelemetry {
    private val processed = AtomicLong()
    private val lastImpact = AtomicReference<ImpactRecord?>()

    fun recordProcessed(records: Collection<ImpactRecord>) {
        if (records.isEmpty()) return
        processed.addAndGet(records.size.toLong())
        lastImpact.set(records.last())
    }

    fun snapshot() = TelemetrySnapshot(
        captured = ImpactQueue.capturedCount(),
        dropped = ImpactQueue.droppedCount(),
        processed = processed.get(),
        lastImpact = lastImpact.get()
    )
}
