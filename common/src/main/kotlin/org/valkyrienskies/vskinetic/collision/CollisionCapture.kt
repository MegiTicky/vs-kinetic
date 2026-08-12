package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.events.CollisionEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

@OptIn(VsBeta::class)
object CollisionCapture {
    private val physicsTick = AtomicLong()
    private val activeEpisodes = ConcurrentHashMap.newKeySet<PairKey>()

    fun nextPhysicsTick() {
        physicsTick.incrementAndGet()
        CollisionTelemetry.recordPhysicsTick()
    }

    fun onStart(event: CollisionEvent) {
        CollisionTelemetry.recordStartEvent()
        capture(event, ImpactPhase.START)
    }

    fun onPersist(event: CollisionEvent) {
        CollisionTelemetry.recordPersistEvent()
        capture(event, ImpactPhase.PERSIST)
    }

    fun onEnd(event: CollisionEvent) {
        CollisionTelemetry.recordEndEvent()
        CollisionTelemetry.recordContactEvent(event.contactPoints.size)
        activeEpisodes.remove(episodeKey(event))
    }

    private fun capture(event: CollisionEvent, phase: ImpactPhase) {
        CollisionTelemetry.recordContactEvent(event.contactPoints.size)
        val bodyA = target(event.shipIdA)
        val bodyB = target(event.shipIdB)
        val aIsShip = event.physLevel.getShipById(event.shipIdA) != null
        val bIsShip = event.physLevel.getShipById(event.shipIdB) != null
        val terrainHasReversedBodyOrder = !aIsShip && bIsShip
        val resolvedBodyA = if (aIsShip) bodyA else CollisionTarget.Ground
        val resolvedBodyB = if (bIsShip) bodyB else CollisionTarget.Ground
        val isGroundCollision = aIsShip != bIsShip
        if (event.contactPoints.isEmpty()) return
        val episode = episodeKey(event, resolvedBodyA, resolvedBodyB)
        val enqueue = activeEpisodes.add(episode)
        if (!enqueue) return

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

            val worldPosition = Vector3d(contact.position.x(), contact.position.y(), contact.position.z())
            val incomingNormalSpeed = (-relativeVelocity.dot(normal)).coerceAtLeast(0.0)
            val relativeSpeed = relativeVelocity.length()
            val alignment = if (relativeSpeed <= 1.0E-8) 0.0 else incomingNormalSpeed / relativeSpeed
            if (isGroundCollision && relativeSpeed > 1.0E-8) {
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
            if (!ImpactQueue.offer(
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
            ) {
                activeEpisodes.remove(episode)
            }
        }
    }

    private fun episodeKey(event: CollisionEvent): PairKey {
        val aIsShip = event.physLevel.getShipById(event.shipIdA) != null
        val bIsShip = event.physLevel.getShipById(event.shipIdB) != null
        return episodeKey(
            event,
            if (aIsShip) CollisionTarget.Body(event.shipIdA) else CollisionTarget.Ground,
            if (bIsShip) CollisionTarget.Body(event.shipIdB) else CollisionTarget.Ground
        )
    }

    private fun episodeKey(event: CollisionEvent, bodyA: CollisionTarget, bodyB: CollisionTarget): PairKey {
        val first = targetKey(bodyA)
        val second = targetKey(bodyB)
        return if (first <= second) PairKey(event.dimensionId, first, second)
        else PairKey(event.dimensionId, second, first)
    }

    private fun targetKey(target: CollisionTarget): Long = when (target) {
        CollisionTarget.Ground -> Long.MIN_VALUE
        is CollisionTarget.Body -> target.id
    }

    private data class PairKey(val dimensionId: String, val low: Long, val high: Long)

    private fun target(id: Long): CollisionTarget =
        CollisionTarget.Body(id)
}
