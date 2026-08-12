package org.valkyrienskies.vskinetic.collision

/** Phase 1 only: drain and record telemetry. No world mutation occurs here. */
object CollisionProcessor {
    fun process(maxRecords: Int = 1024, handler: (Collection<ImpactRecord>) -> Unit = {}) {
        val records = ImpactQueue.drain(maxRecords)
        if (records.isNotEmpty()) handler(records)
        CollisionTelemetry.recordProcessed(records)
    }
}
