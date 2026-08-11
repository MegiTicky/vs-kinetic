package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.world.properties.DimensionId

/** Immutable data copied from a VS collision callback. */
data class ImpactRecord(
    val dimensionId: DimensionId,
    val bodyA: CollisionTarget,
    val bodyB: CollisionTarget,
    val contactPositionWorld: Vector3d,
    val normalWorld: Vector3d,
    val separation: Double,
    val relativeVelocityWorld: Vector3d,
    val physicsTick: Long
) {
    val closingSpeed: Double
        get() = kotlin.math.abs(relativeVelocityWorld.dot(normalWorld))
}
