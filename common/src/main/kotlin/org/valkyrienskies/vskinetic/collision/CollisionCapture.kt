package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.events.CollisionEvent
import java.util.concurrent.atomic.AtomicLong

@OptIn(VsBeta::class)
object CollisionCapture {
    private val physicsTick = AtomicLong()

    fun nextPhysicsTick() {
        physicsTick.incrementAndGet()
        CollisionTelemetry.recordPhysicsTick()
    }

    fun onStart(event: CollisionEvent) {
        CollisionTelemetry.recordStartEvent()
        capture(event)
    }

    fun onPersist(event: CollisionEvent) {
        CollisionTelemetry.recordPersistEvent()
        capture(event)
    }

    fun onEnd(event: CollisionEvent) {
        CollisionTelemetry.recordEndEvent()
        capture(event)
    }

    private fun capture(event: CollisionEvent) {
        CollisionTelemetry.recordContactEvent(event.contactPoints.size)
        val bodyA = target(event.shipIdA)
        val bodyB = target(event.shipIdB)

        event.contactPoints.forEach { contact ->
            val normal = Vector3d(contact.normal.x(), contact.normal.y(), contact.normal.z())
            if (normal.lengthSquared() == 0.0) {
                CollisionTelemetry.recordZeroNormal()
                normal.set(0.0, 1.0, 0.0)
            } else {
                normal.normalize()
            }

            ImpactQueue.offer(
                ImpactRecord(
                    dimensionId = event.dimensionId,
                    bodyA = bodyA,
                    bodyB = bodyB,
                    contactPositionWorld = Vector3d(contact.position.x(), contact.position.y(), contact.position.z()),
                     normalWorld = normal,
                    separation = contact.separation.toDouble(),
                    relativeVelocityWorld = Vector3d(contact.velocity.x(), contact.velocity.y(), contact.velocity.z()),
                     physicsTick = physicsTick.get()
                )
            )
        }
    }

    private fun target(id: Long): CollisionTarget =
        CollisionTarget.Body(id)
}
