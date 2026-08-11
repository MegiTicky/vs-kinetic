package org.valkyrienskies.vskinetic.collision

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TelemetrySnapshot(
    val initialized: Boolean,
    val physicsTicks: Long,
    val startEvents: Long,
    val persistEvents: Long,
    val endEvents: Long,
    val contacts: Long,
    val zeroNormals: Long,
    val captured: Long,
    val dropped: Long,
    val processed: Long,
    val rawProbeTicks: Long,
    val rawEvents: Long,
    val rawProbeFailures: Long,
    val overlapCandidates: Long,
    val lowSpeedCandidates: Long,
    val suppressedCandidates: Long,
    val approximateImpacts: Long,
    val terrainCandidates: Long,
    val terrainLowSpeedCandidates: Long,
    val terrainSuppressedCandidates: Long,
    val approximateTerrainImpacts: Long,
    val lastImpact: ImpactRecord?
)

object CollisionTelemetry {
    private val initialized = java.util.concurrent.atomic.AtomicBoolean()
    private val physicsTicks = AtomicLong()
    private val startEvents = AtomicLong()
    private val persistEvents = AtomicLong()
    private val endEvents = AtomicLong()
    private val contacts = AtomicLong()
    private val zeroNormals = AtomicLong()
    private val processed = AtomicLong()
    private val rawProbeTicks = AtomicLong()
    private val rawEvents = AtomicLong()
    private val rawProbeFailures = AtomicLong()
    private val overlapCandidates = AtomicLong()
    private val lowSpeedCandidates = AtomicLong()
    private val suppressedCandidates = AtomicLong()
    private val approximateImpacts = AtomicLong()
    private val terrainCandidates = AtomicLong()
    private val terrainLowSpeedCandidates = AtomicLong()
    private val terrainSuppressedCandidates = AtomicLong()
    private val approximateTerrainImpacts = AtomicLong()
    private val lastImpact = AtomicReference<ImpactRecord?>()

    fun markInitialized() = initialized.set(true)
    fun recordPhysicsTick() = physicsTicks.incrementAndGet()
    fun recordStartEvent() = startEvents.incrementAndGet()
    fun recordPersistEvent() = persistEvents.incrementAndGet()
    fun recordEndEvent() = endEvents.incrementAndGet()
    fun recordContactEvent(count: Int) = contacts.addAndGet(count.toLong())
    fun recordZeroNormal() = zeroNormals.incrementAndGet()
    fun recordRawProbeTick() = rawProbeTicks.incrementAndGet()
    fun recordRawEvents(count: Int) = rawEvents.addAndGet(count.toLong())
    fun recordRawProbeFailure() = rawProbeFailures.incrementAndGet()
    fun recordOverlapCandidate() = overlapCandidates.incrementAndGet()
    fun recordLowSpeedCandidate() = lowSpeedCandidates.incrementAndGet()
    fun recordSuppressedCandidate() = suppressedCandidates.incrementAndGet()
    fun recordApproximateImpact() = approximateImpacts.incrementAndGet()
    fun recordTerrainCandidate() = terrainCandidates.incrementAndGet()
    fun recordTerrainLowSpeedCandidate() = terrainLowSpeedCandidates.incrementAndGet()
    fun recordTerrainSuppressedCandidate() = terrainSuppressedCandidates.incrementAndGet()
    fun recordApproximateTerrainImpact() = approximateTerrainImpacts.incrementAndGet()

    fun recordProcessed(records: Collection<ImpactRecord>) {
        if (records.isEmpty()) return
        processed.addAndGet(records.size.toLong())
        lastImpact.set(records.last())
    }

    fun snapshot() = TelemetrySnapshot(
        initialized = initialized.get(),
        physicsTicks = physicsTicks.get(),
        startEvents = startEvents.get(),
        persistEvents = persistEvents.get(),
        endEvents = endEvents.get(),
        contacts = contacts.get(),
        zeroNormals = zeroNormals.get(),
        captured = ImpactQueue.capturedCount(),
        dropped = ImpactQueue.droppedCount(),
        processed = processed.get(),
        rawProbeTicks = rawProbeTicks.get(),
        rawEvents = rawEvents.get(),
        rawProbeFailures = rawProbeFailures.get(),
        overlapCandidates = overlapCandidates.get(),
        lowSpeedCandidates = lowSpeedCandidates.get(),
        suppressedCandidates = suppressedCandidates.get(),
        approximateImpacts = approximateImpacts.get(),
        terrainCandidates = terrainCandidates.get(),
        terrainLowSpeedCandidates = terrainLowSpeedCandidates.get(),
        terrainSuppressedCandidates = terrainSuppressedCandidates.get(),
        approximateTerrainImpacts = approximateTerrainImpacts.get(),
        lastImpact = lastImpact.get()
    )
}
