package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.joml.Vector3d
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.vskinetic.collision.CollisionTarget
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import org.valkyrienskies.vskinetic.collision.DebugColors
import org.valkyrienskies.vskinetic.collision.DebugOverlay

/** Bounded, server-thread executor for already validated kinetic damage plans. */
object DamageExecutor {
    private const val MAX_QUEUED_PLANS = 64
    private const val MAX_QUEUED_OPERATIONS = 256
    private const val MAX_OPERATIONS_PER_TICK = 32
    private val queuedPlans = ArrayDeque<QueuedPlan>()
    private var queuedOperations = 0

    var enabled = false
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
    }

    fun enqueue(plan: DamagePlan) {
        if (!enabled) return
        if (queuedPlans.size >= MAX_QUEUED_PLANS || queuedOperations + plan.blocks.size > MAX_QUEUED_OPERATIONS) {
            CollisionTelemetry.recordDamagePlanDropped()
            return
        }
        queuedPlans += QueuedPlan(plan)
        queuedOperations += plan.blocks.size
        CollisionTelemetry.recordDamagePlanQueued()
    }

    fun process(world: ServerShipWorld, levels: Iterable<ServerLevel>) {
        if (!enabled) return
        var processed = 0
        while (processed < MAX_OPERATIONS_PER_TICK) {
            val queued = queuedPlans.firstOrNull() ?: break
            val operation = queued.nextOperation()
            if (operation == null) {
                queuedPlans.removeFirst()
                continue
            }
            queuedOperations--
            processed++
            execute(world, levels, queued.plan, operation)
            if (queued.isComplete) queuedPlans.removeFirst()
        }
    }

    fun pendingOperations(): Int = queuedOperations
    fun pendingPlans(): Int = queuedPlans.size

    private fun clear() {
        queuedPlans.clear()
        queuedOperations = 0
    }

    private fun execute(
        world: ServerShipWorld,
        levels: Iterable<ServerLevel>,
        plan: DamagePlan,
        operation: PlannedBlock
    ) {
        val level = levels.firstOrNull { plan.dimensionId.endsWith(":${it.dimension().location()}") }
        if (level == null || !targetExists(world, plan.target)) {
            marker(operation.position, "unresolved target", DebugColors.UNRESOLVED_TARGET)
            CollisionTelemetry.recordDamageUnresolvedTarget()
            return
        }
        val currentState = level.getBlockState(operation.position)
        if (currentState != operation.expectedState) {
            marker(operation.position, "stale ${operation.position.toShortString()}", DebugColors.STALE)
            CollisionTelemetry.recordDamageStaleStateSkip()
            return
        }
        if (level.getBlockEntity(operation.position) != null) {
            marker(operation.position, "block entity ${operation.position.toShortString()}", DebugColors.BLOCK_ENTITY)
            CollisionTelemetry.recordDamageBlockEntitySkip()
            return
        }

        CollisionTelemetry.recordDamageAttempt()
        if (level.destroyBlock(operation.position, true)) {
            marker(operation.position, "broke ${operation.position.toShortString()}", DebugColors.BROKEN)
            CollisionTelemetry.recordDamageBlockBroken(operation.position.toShortString())
        } else {
            marker(operation.position, "failed ${operation.position.toShortString()}", DebugColors.FAILED)
            CollisionTelemetry.recordDamageFailure(operation.position.toShortString())
        }
    }

    private fun marker(pos: BlockPos, label: String, color: Int) {
        DebugOverlay.record(
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
            label,
            color
        )
    }

    private fun targetExists(world: ServerShipWorld, target: CollisionTarget): Boolean = when (target) {
        CollisionTarget.Ground -> true
        is CollisionTarget.Body -> world.loadedShips.getById(target.id) != null
    }

    private class QueuedPlan(val plan: DamagePlan) {
        private var nextIndex = 0
        val isComplete: Boolean get() = nextIndex >= plan.blocks.size

        fun nextOperation(): PlannedBlock? = plan.blocks.getOrNull(nextIndex++)
    }
}
