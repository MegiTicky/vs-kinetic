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
        capture(event, ImpactPhase.START, true)
    }

    fun onPersist(event: CollisionEvent) {
        CollisionTelemetry.recordPersistEvent()
        capture(event, ImpactPhase.PERSIST, false)
    }

    fun onEnd(event: CollisionEvent) {
        CollisionTelemetry.recordEndEvent()
        capture(event, ImpactPhase.END, false)
    }

    private fun capture(event: CollisionEvent, phase: ImpactPhase, enqueue: Boolean) {
        CollisionTelemetry.recordContactEvent(event.contactPoints.size)
        val bodyA = target(event.shipIdA)
        val bodyB = target(event.shipIdB)
        val aIsShip = event.physLevel.getShipById(event.shipIdA) != null
        val bIsShip = event.physLevel.getShipById(event.shipIdB) != null
        val terrainHasReversedBodyOrder = !aIsShip && bIsShip
        val resolvedBodyA = if (aIsShip) bodyA else CollisionTarget.Ground
        val resolvedBodyB = if (bIsShip) bodyB else CollisionTarget.Ground
        val isGroundCollision = aIsShip != bIsShip

        event.contactPoints.forEach { contact ->
            val normal = Vector3d(contact.normal.x(), contact.normal.y(), contact.normal.z())
            if (normal.lengthSquared() == 0.0) {
                CollisionTelemetry.recordZeroNormal()
                normal.set(0.0, 1.0, 0.0)
            } else {
                normal.normalize()
            }
            val relativeVelocity = Vector3d(contact.velocity.x(), contact.velocity.y(), contact.velocity.z())
            if (terrainHasReversedBodyOrder) {
                normal.negate()
                relativeVelocity.negate()
            }

            if (enqueue) {
                val worldPosition = Vector3d(contact.position.x(), contact.position.y(), contact.position.z())
                val incomingNormalSpeed = (-relativeVelocity.dot(normal)).coerceAtLeast(0.0)
                val relativeSpeed = relativeVelocity.length()
                val alignment = if (relativeSpeed <= 1.0E-8) 0.0 else incomingNormalSpeed / relativeSpeed
                if (isGroundCollision &&
                    relativeSpeed > 1.0E-8
                ) {
                    DebugOverlay.record(
                        worldPosition,
                        "VS ground n=${"%.1f".format(incomingNormalSpeed)} align=${"%.2f".format(alignment)}",
                        DebugColors.VS_CONTACT,
                        normal,
                        DebugMarkerStyle.POINT
                    )
                    DebugOverlay.record(
                        worldPosition,
                        "VS ground velocity=${"%.1f".format(relativeSpeed)} m/s",
                        DebugColors.VS_CONTACT_VELOCITY,
                        Vector3d(relativeVelocity).normalize(),
                        DebugMarkerStyle.POINT
                    )
                } else if (!isGroundCollision) {
                    DebugOverlay.record(
                        worldPosition,
                        "VS contact incoming=${"%.1f".format(incomingNormalSpeed)} m/s",
                        DebugColors.VS_CONTACT,
                        normal,
                        DebugMarkerStyle.POINT
                    )
                }
                ImpactQueue.offer(
                    ImpactRecord(
                        dimensionId = event.dimensionId,
                        bodyA = resolvedBodyA,
                        bodyB = resolvedBodyB,
                        contactPositionWorld = worldPosition,
                        normalWorld = normal,
                        separation = contact.separation.toDouble(),
                        relativeVelocityWorld = relativeVelocity,
                        physicsTick = physicsTick.get(),
                        source = ImpactSource.AUTHORITATIVE,
                        phase = phase
                    )
                )
            }
        }
    }

    private fun target(id: Long): CollisionTarget =
        CollisionTarget.Body(id)
}
