package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.events.PhysTickEvent
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.internal.world.VsiPhysLevel
import java.util.concurrent.atomic.AtomicReference

/** Physics-thread rigid-body snapshots captured before the solver advances a level. */
object CollisionMotionHistory {
    private val current = AtomicReference<Map<SnapshotKey, MotionState>>(emptyMap())
    private val previous = AtomicReference<Map<SnapshotKey, MotionState>>(emptyMap())

    fun capture(event: PhysTickEvent, physicsTick: Long) {
        val level = event.world as? VsiPhysLevel ?: return
        val dimensionId = level.dimension
        val snapshots = HashMap<SnapshotKey, MotionState>()
        for (ship in level.getAllPhysShips()) {
            snapshots[SnapshotKey(dimensionId, ship.id)] = state(ship, physicsTick)
        }
        val oldCurrent = current.get()
        previous.set(oldCurrent.filterKeys { it.dimensionId == dimensionId })
        current.set(oldCurrent.filterKeys { it.dimensionId != dimensionId } + snapshots)
    }

    fun resolveShipPair(
        dimensionId: String,
        shipAId: Long,
        shipBId: Long,
        contactPositionWorld: Vector3d,
        callbackVelocityWorld: Vector3d,
        contactNormalWorld: Vector3d,
        physicsTick: Long
    ): VelocityResolution {
        val keyA = SnapshotKey(dimensionId, shipAId)
        val keyB = SnapshotKey(dimensionId, shipBId)
        val currentA = current.get()[keyA]
        val currentB = current.get()[keyB]
        val normal = Vector3d(contactNormalWorld)
        val callbackSpeed = normalSpeed(callbackVelocityWorld, normal)
        if (currentA != null && currentB != null) {
            val motionVelocity = relative(currentA, currentB, contactPositionWorld)
            return VelocityResolution(
                relativeVelocity = motionVelocity,
                source = VelocitySource.PHYSICS_PRE_STEP,
                callbackNormalSpeed = callbackSpeed,
                selectedNormalSpeed = normalSpeed(motionVelocity, normal),
                snapshotAge = 0L
            )
        }

        val previousA = previous.get()[keyA]
        val previousB = previous.get()[keyB]
        if (previousA != null && previousB != null && previousA.physicsTick == physicsTick - 1L && previousB.physicsTick == physicsTick - 1L) {
            val motionVelocity = relative(previousA, previousB, contactPositionWorld)
            return VelocityResolution(
                relativeVelocity = motionVelocity,
                source = VelocitySource.PHYSICS_PREVIOUS,
                callbackNormalSpeed = callbackSpeed,
                selectedNormalSpeed = normalSpeed(motionVelocity, normal),
                snapshotAge = 1L
            )
        }

        return VelocityResolution(
            relativeVelocity = Vector3d(callbackVelocityWorld),
            source = VelocitySource.CALLBACK,
            callbackNormalSpeed = callbackSpeed,
            selectedNormalSpeed = callbackSpeed,
            snapshotAge = null
        )
    }

    private fun state(ship: PhysShip, physicsTick: Long): MotionState {
        val centerOfMassWorld = ship.transform.shipToWorld.transformPosition(ship.centerOfMass, Vector3d())
        return MotionState(
            linearVelocity = Vector3d(ship.velocity),
            angularVelocity = Vector3d(ship.angularVelocity),
            centerOfMassWorld = centerOfMassWorld,
            physicsTick = physicsTick
        )
    }

    private fun relative(a: MotionState, b: MotionState, position: Vector3d): Vector3d =
        velocityAt(a, position).sub(velocityAt(b, position))

    private fun velocityAt(state: MotionState, position: Vector3d): Vector3d =
        Vector3d(state.linearVelocity).add(
            Vector3d(state.angularVelocity).cross(Vector3d(position).sub(state.centerOfMassWorld))
        )

    private fun normalSpeed(velocity: Vector3d, normal: Vector3d): Double {
        if (normal.lengthSquared() <= 1.0E-8) return 0.0
        normal.normalize()
        return kotlin.math.abs(velocity.dot(normal))
    }

    enum class VelocitySource {
        PHYSICS_PRE_STEP,
        PHYSICS_PREVIOUS,
        CALLBACK
    }

    data class VelocityResolution(
        val relativeVelocity: Vector3d,
        val source: VelocitySource,
        val callbackNormalSpeed: Double,
        val selectedNormalSpeed: Double,
        val snapshotAge: Long?
    )

    private data class SnapshotKey(val dimensionId: String, val shipId: Long)

    private data class MotionState(
        val linearVelocity: Vector3d,
        val angularVelocity: Vector3d,
        val centerOfMassWorld: Vector3d,
        val physicsTick: Long
    )
}
