package org.valkyrienskies.vskinetic.collision

/** Phase 1 only: drain and record telemetry. No world mutation occurs here. */
object CollisionProcessor {
    fun process(maxRecords: Int = 1024) {
        val records = ImpactQueue.drain(maxRecords)
        CollisionTelemetry.recordProcessed(records)
    }
}
