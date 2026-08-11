package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.events.CollisionEvent

@OptIn(VsBeta::class)
object CollisionCapture {
    private var physicsTick: Long = 0

    fun nextPhysicsTick() {
        physicsTick++
    }

    fun capture(event: CollisionEvent) {
        val bodyA = target(event.shipIdA)
        val bodyB = target(event.shipIdB)

        event.contactPoints.forEach { contact ->
            val normal = Vector3d(contact.normal.x(), contact.normal.y(), contact.normal.z())
            if (normal.lengthSquared() == 0.0) return@forEach

            ImpactQueue.offer(
                ImpactRecord(
                    dimensionId = event.dimensionId,
                    bodyA = bodyA,
                    bodyB = bodyB,
                    contactPositionWorld = Vector3d(contact.position.x(), contact.position.y(), contact.position.z()),
                    normalWorld = normal.normalize(),
                    separation = contact.separation.toDouble(),
                    relativeVelocityWorld = Vector3d(contact.velocity.x(), contact.velocity.y(), contact.velocity.z()),
                    physicsTick = physicsTick
                )
            )
        }
    }

    private fun target(id: Long): CollisionTarget =
        CollisionTarget.Body(id)
}
