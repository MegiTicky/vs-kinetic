package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld

/** Approximate game-tick fallback for ship-to-ship contacts when VS collision events are silent. */
@OptIn(VsBeta::class)
object ShipPairCollisionDetector {
    private const val MIN_CLOSING_SPEED = 0.5
    private const val MAX_TRACKED_PAIRS = 8192
    private val overlappingPairs = LinkedHashMap<PairKey, PairState>()

    fun scan(world: ServerShipWorld) {
        val ships = world.loadedShips.toList()
        val currentPairs = LinkedHashMap<PairKey, PairState>()

        for (index in ships.indices) {
            val shipA = ships[index]
            if (shipA.isStatic) continue
            val boundsA = bounds(shipA) ?: continue
            for (otherIndex in index + 1 until ships.size) {
                val shipB = ships[otherIndex]
                if (shipB.isStatic || shipA.chunkClaimDimension != shipB.chunkClaimDimension) continue
                val boundsB = bounds(shipB) ?: continue
                if (!overlaps(boundsA, boundsB)) continue

                val key = PairKey.of(shipA.id, shipB.id)
                val velocity = Vector3d(shipA.velocity).sub(shipB.velocity)
                val direction = Vector3d(boundsB.center).sub(boundsA.center)
                if (direction.lengthSquared() == 0.0) direction.set(0.0, 1.0, 0.0) else direction.normalize()
                val closingSpeed = velocity.dot(direction)
                val previousState = overlappingPairs[key]
                val state = PairState(closingSpeed)
                currentPairs[key] = state
                CollisionTelemetry.recordOverlapCandidate()
                if (closingSpeed < MIN_CLOSING_SPEED) {
                    CollisionTelemetry.recordLowSpeedCandidate()
                    continue
                }
                if (previousState != null && previousState.closingSpeed >= MIN_CLOSING_SPEED) {
                    CollisionTelemetry.recordSuppressedCandidate()
                    continue
                }
                if (previousState == null && overlappingPairs.size >= MAX_TRACKED_PAIRS) continue

                val contact = intersectionCenter(boundsA, boundsB)
                DebugOverlay.record(
                    contact,
                    "pair contact closing=${"%.1f".format(closingSpeed)} m/s",
                    DebugColors.PAIR_CONTACT,
                    direction
                )
                ImpactQueue.offer(
                    ImpactRecord(
                        dimensionId = shipA.chunkClaimDimension,
                        bodyA = CollisionTarget.Body(shipA.id),
                        bodyB = CollisionTarget.Body(shipB.id),
                        contactPositionWorld = contact,
                        normalWorld = direction,
                        separation = 0.0,
                        relativeVelocityWorld = velocity,
                        physicsTick = 0L,
                        source = ImpactSource.APPROXIMATE,
                        phase = ImpactPhase.START
                    )
                )
                CollisionTelemetry.recordApproximateImpact()
            }
        }

        overlappingPairs.clear()
        overlappingPairs.putAll(currentPairs)
        while (overlappingPairs.size > MAX_TRACKED_PAIRS) {
            overlappingPairs.remove(overlappingPairs.keys.first())
        }
    }

    private fun overlaps(a: Bounds, b: Bounds): Boolean =
        a.maxX > b.minX && a.minX < b.maxX &&
            a.maxY > b.minY && a.minY < b.maxY &&
            a.maxZ > b.minZ && a.minZ < b.maxZ

    private fun intersectionCenter(a: Bounds, b: Bounds): Vector3d = Vector3d(
        (kotlin.math.max(a.minX, b.minX) + kotlin.math.min(a.maxX, b.maxX)) * 0.5,
        (kotlin.math.max(a.minY, b.minY) + kotlin.math.min(a.maxY, b.maxY)) * 0.5,
        (kotlin.math.max(a.minZ, b.minZ) + kotlin.math.min(a.maxZ, b.maxZ)) * 0.5
    )

    private fun bounds(ship: LoadedServerShip): Bounds? = try {
        val aabb = ship.javaClass.getMethod("getWorldAABB").invoke(ship)
        val type = aabb.javaClass
        fun number(name: String): Double = (type.getMethod(name).invoke(aabb) as Number).toDouble()
        Bounds(
            number("minX"), number("minY"), number("minZ"),
            number("maxX"), number("maxY"), number("maxZ")
        )
    } catch (_: Throwable) {
        null
    }

    private data class Bounds(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double
    ) {
        val center: Vector3d
            get() = Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
    }

    private data class PairState(val closingSpeed: Double)

    private data class PairKey(val low: Long, val high: Long) {
        companion object {
            fun of(a: Long, b: Long) = if (a < b) PairKey(a, b) else PairKey(b, a)
        }
    }
}
