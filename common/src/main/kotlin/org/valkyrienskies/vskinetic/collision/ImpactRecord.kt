package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.world.properties.DimensionId
import kotlin.math.abs

enum class ImpactSource {
    AUTHORITATIVE,
    APPROXIMATE
}

enum class ImpactPhase {
    START,
    PERSIST,
    END
}

/** Immutable data copied from a VS collision callback. */
data class ImpactRecord(
    val dimensionId: DimensionId,
    val bodyA: CollisionTarget,
    val bodyB: CollisionTarget,
    val contactPositionWorld: Vector3d,
    val normalWorld: Vector3d,
    val separation: Double,
    val relativeVelocityWorld: Vector3d,
    val physicsTick: Long,
    /** Optional server-side hint for the first terrain block at an approximate contact. */
    val contactBlockPosition: Long? = null,
    val source: ImpactSource = ImpactSource.AUTHORITATIVE,
    val phase: ImpactPhase = ImpactPhase.START
) {
    /** Relative speed into the contact surface. Separating motion is not impact energy. */
    val incomingNormalSpeed: Double
        get() {
            val normalComponent = -relativeVelocityWorld.dot(normalWorld)
            return if (bodyA is CollisionTarget.Body && bodyB is CollisionTarget.Body) {
                // PhysX does not guarantee a stable normal orientation for ship pairs.
                abs(normalComponent)
            } else {
                normalComponent.coerceAtLeast(0.0)
            }
        }

    val closingSpeed: Double
        get() = incomingNormalSpeed

    /** Fraction of relative motion directed along the contact normal. */
    val normalAlignment: Double
        get() {
            val speed = relativeVelocityWorld.length()
            return if (speed <= 1.0E-8) 0.0 else (incomingNormalSpeed / speed).coerceIn(0.0, 1.0)
        }
}
