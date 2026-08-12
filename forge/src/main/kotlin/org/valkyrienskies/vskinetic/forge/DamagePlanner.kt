package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.vskinetic.collision.CollisionTarget
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import org.valkyrienskies.vskinetic.collision.DebugColors
import org.valkyrienskies.vskinetic.collision.DebugOverlay
import org.valkyrienskies.vskinetic.collision.ImpactRecord
import org.valkyrienskies.vskinetic.collision.ImpactSource

/** Read-only point-impact planner. It never mutates a level or removes a block. */
object DamagePlanner {
    private const val MAX_RECORDS_PER_TICK = 128
    private const val MAX_BLOCKS_PER_PLAN = 8
    private const val MAX_ENERGY = 1.0e9
    private const val ENERGY_SCALE = 0.01
    private const val MIN_PLAN_ENERGY = 1.0

    fun plan(
        records: Collection<ImpactRecord>,
        world: ServerShipWorld,
        levels: Iterable<ServerLevel>
    ) {
        records.asSequence()
            .groupBy { collisionKey(it, world) }
            .mapNotNull { (_, group) ->
                group.maxWithOrNull(
                    compareBy<ImpactRecord> { it.closingSpeed }
                        .thenBy { if (it.source == ImpactSource.AUTHORITATIVE) 1 else 0 }
                )
            }
            .sortedBy { if (it.source == ImpactSource.AUTHORITATIVE) 0 else 1 }
            .take(MAX_RECORDS_PER_TICK)
            .forEach { impact ->
            CollisionTelemetry.recordPlanEvaluated()
            if (impact.source == ImpactSource.AUTHORITATIVE) {
                CollisionTelemetry.recordPlanAuthoritative()
            } else {
                CollisionTelemetry.recordPlanApproximate()
            }
            CollisionTelemetry.recordLastImpact(impact)
            val level = levels.firstOrNull { belongsToLevel(impact.dimensionId, it) }
            if (level == null) {
                DebugOverlay.record(
                    impact.contactPositionWorld,
                    "reject: no level for ${impact.dimensionId}",
                    DebugColors.UNRESOLVED
                )
                CollisionTelemetry.recordUnresolvedContact()
                return@forEach
            }

            val movingShip = movingShip(impact, world)
            val target = target(impact, world)
            val energy = impactEnergy(impact, movingShip, target, world).coerceIn(0.0, MAX_ENERGY)
            if (energy < MIN_PLAN_ENERGY) {
                DebugOverlay.record(
                    impact.contactPositionWorld,
                    "reject: low energy (${"%.1f".format(energy)})",
                    DebugColors.PLAN_REJECT
                )
                CollisionTelemetry.recordPlanRejectedLowEnergy()
                return@forEach
            }

            val candidates = resolveCandidates(level, world, impact, target)
            if (candidates.isEmpty()) {
                DebugOverlay.record(
                    impact.contactPositionWorld,
                    "reject: no candidate blocks",
                    DebugColors.UNRESOLVED
                )
                CollisionTelemetry.recordUnresolvedContact()
                return@forEach
            }
            val operations = ArrayList<PlannedBlock>(MAX_BLOCKS_PER_PLAN)
            var remainingEnergy = energy
            for (candidate in candidates) {
                CollisionTelemetry.recordCandidateBlock()
                val material = MaterialProfileResolver.resolve(level, candidate.position, candidate.state)
                if (!material.canBreak || material.treatment == MaterialTreatment.NO_DAMAGE) continue
                val cost = material.toughness.coerceAtMost(MAX_ENERGY)
                if (remainingEnergy < cost) break
                operations += PlannedBlock(target, candidate.position, candidate.state, cost, material)
                remainingEnergy -= cost
            }
            if (operations.isEmpty()) {
                DebugOverlay.record(
                    impact.contactPositionWorld,
                    "reject: nothing breakable (${"%.1f".format(energy)} energy)",
                    DebugColors.PLAN_REJECT
                )
                CollisionTelemetry.recordPlanRejectedLowEnergy()
                return@forEach
            }
            if (candidates.size >= MAX_BLOCKS_PER_PLAN && operations.size >= MAX_BLOCKS_PER_PLAN) {
                CollisionTelemetry.recordCappedPlan()
            }
            val plan = DamagePlan(impact.dimensionId, target, energy, operations)
            CollisionTelemetry.recordPlanCreated(plan.blocks.size)
            val first = plan.blocks.first()
            DebugOverlay.record(
                Vector3d(first.position.x + 0.5, first.position.y + 0.5, first.position.z + 0.5),
                "plan ${plan.blocks.size} blocks E=${"%.0f".format(plan.energyBudget)} src=${impact.source}",
                DebugColors.PLAN
            )
            for (block in plan.blocks.drop(1)) {
                DebugOverlay.record(
                    Vector3d(block.position.x + 0.5, block.position.y + 0.5, block.position.z + 0.5),
                    block.position.toShortString(),
                    DebugColors.PLAN
                )
            }
            DamageExecutor.enqueue(plan)
            CollisionTelemetry.recordLastPlan(
                "source=${impact.source}, target=${plan.target}, energy=${"%.2f".format(plan.energyBudget)}, " +
                    "blocks=${plan.blocks.size}, first=${plan.blocks.first().position}, " +
                    "resistance=${"%.2f".format(plan.blocks.first().material.toughness)}"
            )
        }
    }

    private fun movingShip(impact: ImpactRecord, world: ServerShipWorld): LoadedServerShip? {
        val candidateIds = listOfNotNull(
            (impact.bodyA as? CollisionTarget.Body)?.id,
            (impact.bodyB as? CollisionTarget.Body)?.id
        )
        return candidateIds.asSequence().mapNotNull { world.loadedShips.getById(it) }.firstOrNull()
    }

    private fun collisionKey(impact: ImpactRecord, world: ServerShipWorld): Pair<Long, Long> {
        fun bodyKey(target: CollisionTarget): Long = when (target) {
            CollisionTarget.Ground -> Long.MIN_VALUE
            is CollisionTarget.Body -> if (world.loadedShips.getById(target.id) != null) target.id else Long.MIN_VALUE
        }
        val a = bodyKey(impact.bodyA)
        val b = bodyKey(impact.bodyB)
        return if (a <= b) a to b else b to a
    }

    private fun target(impact: ImpactRecord, world: ServerShipWorld): CollisionTarget {
        if (impact.bodyA is CollisionTarget.Ground || impact.bodyB is CollisionTarget.Ground) {
            return CollisionTarget.Ground
        }
        val bodyA = impact.bodyA as? CollisionTarget.Body
        val bodyB = impact.bodyB as? CollisionTarget.Body
        val aLoaded = bodyA?.let { world.loadedShips.getById(it.id) != null } == true
        val bLoaded = bodyB?.let { world.loadedShips.getById(it.id) != null } == true
        if (aLoaded != bLoaded) return CollisionTarget.Ground
        return impact.bodyB
    }

    private fun impactEnergy(
        impact: ImpactRecord,
        movingShip: LoadedServerShip?,
        target: CollisionTarget,
        world: ServerShipWorld
    ): Double {
        val speed = impact.closingSpeed
        val movingMass = movingShip?.inertiaData?.mass?.coerceAtLeast(0.0) ?: 0.0
        val targetMass = when (target) {
            CollisionTarget.Ground -> Double.POSITIVE_INFINITY
            is CollisionTarget.Body -> world.loadedShips.getById(target.id)?.inertiaData?.mass
                ?.coerceAtLeast(0.0) ?: movingMass
        }
        val effectiveMass = if (!targetMass.isFinite()) movingMass else {
            (movingMass * targetMass / (movingMass + targetMass).coerceAtLeast(1.0))
        }
        return 0.5 * effectiveMass * speed * speed * ENERGY_SCALE
    }

    private fun resolveCandidates(
        level: ServerLevel,
        world: ServerShipWorld,
        impact: ImpactRecord,
        target: CollisionTarget
    ): List<Candidate> {
        val targetShip = target as? CollisionTarget.Body
        val ship = targetShip?.let { world.loadedShips.getById(it.id) }
        val localContact = if (ship != null) {
            ship.worldToShip.transformPosition(impact.contactPositionWorld, Vector3d())
        } else {
            impact.contactPositionWorld
        }
        val direction = Vector3d(impact.normalWorld)
        if (direction.lengthSquared() == 0.0) direction.set(0.0, 1.0, 0.0) else direction.normalize()
        if (ship != null) ship.worldToShip.transformDirection(direction)
        direction.normalize()
        val incomingDirection = if (target == CollisionTarget.Ground) groundDirection(impact, direction) else Vector3d(direction)
        val candidates = ArrayList<Candidate>(MAX_BLOCKS_PER_PLAN)
        val seen = HashSet<BlockPos>()
        val hintedPosition = if (target == CollisionTarget.Ground) {
            impact.contactBlockPosition?.let { BlockPos.of(it) }
                ?: TerrainBlockResolver.resolve(level, impact.contactPositionWorld, incomingDirection)
        } else {
            null
        }
        val firstDepth = if (hintedPosition != null && target == CollisionTarget.Ground) {
            val state = level.getBlockState(hintedPosition)
            if (!state.isAir && !state.getCollisionShape(level, hintedPosition, CollisionContext.empty()).isEmpty()) {
                seen += hintedPosition
                candidates += Candidate(hintedPosition, state)
                1
            } else {
                0
            }
        } else {
            0
        }
        for (depth in firstDepth until MAX_BLOCKS_PER_PLAN) {
            val searchCenter = if (hintedPosition != null && target == CollisionTarget.Ground) {
                Vector3d(hintedPosition.x + 0.5, hintedPosition.y + 0.5, hintedPosition.z + 0.5)
                    .fma(depth.toDouble(), incomingDirection)
            } else {
                Vector3d(localContact).fma(0.5 + depth, incomingDirection)
            }
            val center = BlockPos.containing(searchCenter.x, searchCenter.y, searchCenter.z)
            var found: Candidate? = null
            val state = level.getBlockState(center)
            if (seen.add(center) && !state.isAir && !state.getCollisionShape(level, center, CollisionContext.empty()).isEmpty()) {
                found = Candidate(center, state)
            }
            if (found == null) break
            candidates += found
        }
        return candidates
    }

    private fun groundDirection(impact: ImpactRecord, normal: Vector3d): Vector3d {
        val relativeVelocity = Vector3d(impact.relativeVelocityWorld)
        if (relativeVelocity.lengthSquared() > 1.0E-8) {
            val direction = relativeVelocity.normalize()
            return cardinalDirection(direction)
        }
        return cardinalDirection(Vector3d(normal).negate())
    }

    private fun cardinalDirection(vector: Vector3d): Vector3d {
        val absX = kotlin.math.abs(vector.x)
        val absY = kotlin.math.abs(vector.y)
        val absZ = kotlin.math.abs(vector.z)
        return when {
            absX >= absY && absX >= absZ && absX > 1.0E-8 -> Vector3d(kotlin.math.sign(vector.x), 0.0, 0.0)
            absY >= absZ && absY > 1.0E-8 -> Vector3d(0.0, kotlin.math.sign(vector.y), 0.0)
            absZ > 1.0E-8 -> Vector3d(0.0, 0.0, kotlin.math.sign(vector.z))
            else -> Vector3d(0.0, -1.0, 0.0)
        }
    }

    private fun belongsToLevel(dimensionId: String, level: ServerLevel): Boolean =
        dimensionId.endsWith(":${level.dimension().location()}")

    private data class Candidate(val position: BlockPos, val state: net.minecraft.world.level.block.state.BlockState)
}
