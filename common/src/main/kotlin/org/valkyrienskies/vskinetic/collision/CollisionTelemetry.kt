package org.valkyrienskies.vskinetic.collision

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TelemetrySnapshot(
    val initialized: Boolean,
    val authoritativeOnly: Boolean,
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
    val stopEvents: Long,
    val stopImpacts: Long,
    val stopNoContact: Long,
    val plansEvaluated: Long,
    val plansCreated: Long,
    val plansAuthoritative: Long,
    val plansApproximate: Long,
    val authoritativeEpisodeRearms: Long,
    val authoritativeEpisodeSuppressed: Long,
    val motionVelocityUses: Long,
    val callbackVelocityUses: Long,
    val missingMotionSnapshots: Long,
    val lastVelocityResolution: String?,
    val shipPairCandidateProbes: Long,
    val shipPairCandidatesResolved: Long,
    val lastShipPairCandidate: String?,
    val plansRejectedLowEnergy: Long,
    val unresolvedContacts: Long,
    val candidateBlocks: Long,
    val plannedBlocks: Long,
    val cappedPlans: Long,
    val damageEnabled: Boolean,
    val damagePlansQueued: Long,
    val damagePlansDropped: Long,
    val damageOperationsAttempted: Long,
    val damageBlocksBroken: Long,
    val damageStaleStateSkips: Long,
    val damageBlockEntitySkips: Long,
    val damageUnresolvedTargets: Long,
    val damageFailures: Long,
    val lastExecution: String?,
    val lastPlan: String?,
    val lastAuthoritativeEvent: String?,
    val lastRawProbeFailure: String?,
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
    private val stopEvents = AtomicLong()
    private val stopImpacts = AtomicLong()
    private val stopNoContact = AtomicLong()
    private val plansEvaluated = AtomicLong()
    private val plansCreated = AtomicLong()
    private val plansAuthoritative = AtomicLong()
    private val plansApproximate = AtomicLong()
    private val authoritativeEpisodeRearms = AtomicLong()
    private val authoritativeEpisodeSuppressed = AtomicLong()
    private val motionVelocityUses = AtomicLong()
    private val callbackVelocityUses = AtomicLong()
    private val missingMotionSnapshots = AtomicLong()
    private val shipPairCandidateProbes = AtomicLong()
    private val shipPairCandidatesResolved = AtomicLong()
    private val plansRejectedLowEnergy = AtomicLong()
    private val unresolvedContacts = AtomicLong()
    private val candidateBlocks = AtomicLong()
    private val plannedBlocks = AtomicLong()
    private val cappedPlans = AtomicLong()
    private val damageEnabled = java.util.concurrent.atomic.AtomicBoolean()
    private val damagePlansQueued = AtomicLong()
    private val damagePlansDropped = AtomicLong()
    private val damageOperationsAttempted = AtomicLong()
    private val damageBlocksBroken = AtomicLong()
    private val damageStaleStateSkips = AtomicLong()
    private val damageBlockEntitySkips = AtomicLong()
    private val damageUnresolvedTargets = AtomicLong()
    private val damageFailures = AtomicLong()
    private val lastImpact = AtomicReference<ImpactRecord?>()
    private val lastPlan = AtomicReference<String?>()
    private val lastAuthoritativeEvent = AtomicReference<String?>()
    private val lastVelocityResolution = AtomicReference<String?>()
    private val lastShipPairCandidate = AtomicReference<String?>()
    private val lastRawProbeFailure = AtomicReference<String?>()
    private val lastExecution = AtomicReference<String?>()

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
    fun recordRawProbeFailure(failure: Throwable) = lastRawProbeFailure.set(
        "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
    ).also { rawProbeFailures.incrementAndGet() }
    fun recordOverlapCandidate() = overlapCandidates.incrementAndGet()
    fun recordLowSpeedCandidate() = lowSpeedCandidates.incrementAndGet()
    fun recordSuppressedCandidate() = suppressedCandidates.incrementAndGet()
    fun recordApproximateImpact() = approximateImpacts.incrementAndGet()
    fun recordTerrainCandidate() = terrainCandidates.incrementAndGet()
    fun recordTerrainLowSpeedCandidate() = terrainLowSpeedCandidates.incrementAndGet()
    fun recordTerrainSuppressedCandidate() = terrainSuppressedCandidates.incrementAndGet()
    fun recordApproximateTerrainImpact() = approximateTerrainImpacts.incrementAndGet()
    fun recordStopEvent() = stopEvents.incrementAndGet()
    fun recordStopImpact() = stopImpacts.incrementAndGet()
    fun recordStopNoContact() = stopNoContact.incrementAndGet()
    fun recordPlanEvaluated() = plansEvaluated.incrementAndGet()
    fun recordPlanAuthoritative() = plansAuthoritative.incrementAndGet()
    fun recordPlanApproximate() = plansApproximate.incrementAndGet()
    fun recordAuthoritativeEpisodeRearm() = authoritativeEpisodeRearms.incrementAndGet()
    fun recordAuthoritativeEpisodeSuppressed() = authoritativeEpisodeSuppressed.incrementAndGet()
    fun recordVelocityResolution(resolution: CollisionMotionHistory.VelocityResolution) {
        when (resolution.source) {
            CollisionMotionHistory.VelocitySource.PHYSICS_PRE_STEP,
            CollisionMotionHistory.VelocitySource.PHYSICS_PREVIOUS -> motionVelocityUses.incrementAndGet()
            CollisionMotionHistory.VelocitySource.CALLBACK -> {
                callbackVelocityUses.incrementAndGet()
                missingMotionSnapshots.incrementAndGet()
            }
        }
        lastVelocityResolution.set(
            "source=${resolution.source}, callbackNormal=${"%.3f".format(java.util.Locale.ROOT, resolution.callbackNormalSpeed)}, " +
                "selectedNormal=${"%.3f".format(java.util.Locale.ROOT, resolution.selectedNormalSpeed)}, " +
                "snapshotAge=${resolution.snapshotAge ?: "missing"}"
        )
    }
    fun recordLastImpact(impact: ImpactRecord) = lastImpact.set(impact)
    fun recordShipPairCandidateProbe() = shipPairCandidateProbes.incrementAndGet()
    fun recordShipPairCandidateResolved(shipId: Long, position: net.minecraft.core.BlockPos) {
        shipPairCandidatesResolved.incrementAndGet()
        lastShipPairCandidate.set("ship=$shipId, pos=${position.toShortString()}")
    }
    fun recordAuthoritativeEvent(summary: String) = lastAuthoritativeEvent.set(summary)
    fun recordPlanCreated(blockCount: Int) {
        plansCreated.incrementAndGet()
        plannedBlocks.addAndGet(blockCount.toLong())
    }
    fun recordPlanRejectedLowEnergy() = plansRejectedLowEnergy.incrementAndGet()
    fun recordUnresolvedContact() = unresolvedContacts.incrementAndGet()
    fun recordCandidateBlock() = candidateBlocks.incrementAndGet()
    fun recordCappedPlan() = cappedPlans.incrementAndGet()
    fun recordLastPlan(summary: String) = lastPlan.set(summary)
    fun setDamageEnabled(value: Boolean) = damageEnabled.set(value)
    fun recordDamagePlanQueued() = damagePlansQueued.incrementAndGet()
    fun recordDamagePlanDropped() = damagePlansDropped.incrementAndGet()
    fun recordDamageAttempt() = damageOperationsAttempted.incrementAndGet()
    fun recordDamageBlockBroken(position: String) {
        damageBlocksBroken.incrementAndGet()
        lastExecution.set("broke=$position")
    }
    fun recordDamageStaleStateSkip() = damageStaleStateSkips.incrementAndGet()
    fun recordDamageBlockEntitySkip() = damageBlockEntitySkips.incrementAndGet()
    fun recordDamageUnresolvedTarget() = damageUnresolvedTargets.incrementAndGet()
    fun recordDamageFailure(position: String) {
        damageFailures.incrementAndGet()
        lastExecution.set("failed=$position")
    }

    fun recordProcessed(records: Collection<ImpactRecord>) {
        if (records.isEmpty()) return
        processed.addAndGet(records.size.toLong())
        lastImpact.set(records.last())
    }

    fun snapshot() = TelemetrySnapshot(
        initialized = initialized.get(),
        authoritativeOnly = CollisionExperiment.isAuthoritativeOnly(),
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
        stopEvents = stopEvents.get(),
        stopImpacts = stopImpacts.get(),
        stopNoContact = stopNoContact.get(),
        plansEvaluated = plansEvaluated.get(),
        plansCreated = plansCreated.get(),
        plansAuthoritative = plansAuthoritative.get(),
        plansApproximate = plansApproximate.get(),
        authoritativeEpisodeRearms = authoritativeEpisodeRearms.get(),
        authoritativeEpisodeSuppressed = authoritativeEpisodeSuppressed.get(),
        motionVelocityUses = motionVelocityUses.get(),
        callbackVelocityUses = callbackVelocityUses.get(),
        missingMotionSnapshots = missingMotionSnapshots.get(),
        lastVelocityResolution = lastVelocityResolution.get(),
        shipPairCandidateProbes = shipPairCandidateProbes.get(),
        shipPairCandidatesResolved = shipPairCandidatesResolved.get(),
        lastShipPairCandidate = lastShipPairCandidate.get(),
        plansRejectedLowEnergy = plansRejectedLowEnergy.get(),
        unresolvedContacts = unresolvedContacts.get(),
        candidateBlocks = candidateBlocks.get(),
        plannedBlocks = plannedBlocks.get(),
        cappedPlans = cappedPlans.get(),
        damageEnabled = damageEnabled.get(),
        damagePlansQueued = damagePlansQueued.get(),
        damagePlansDropped = damagePlansDropped.get(),
        damageOperationsAttempted = damageOperationsAttempted.get(),
        damageBlocksBroken = damageBlocksBroken.get(),
        damageStaleStateSkips = damageStaleStateSkips.get(),
        damageBlockEntitySkips = damageBlockEntitySkips.get(),
        damageUnresolvedTargets = damageUnresolvedTargets.get(),
        damageFailures = damageFailures.get(),
        lastExecution = lastExecution.get(),
        lastPlan = lastPlan.get(),
        lastAuthoritativeEvent = lastAuthoritativeEvent.get(),
        lastRawProbeFailure = lastRawProbeFailure.get(),
        lastImpact = lastImpact.get()
    )
}
