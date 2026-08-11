package org.valkyrienskies.vskinetic.forge

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.vskinetic.collision.CollisionTarget
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import org.valkyrienskies.vskinetic.collision.ImpactQueue
import org.valkyrienskies.vskinetic.collision.ImpactRecord

/** Server-tick approximation for dynamic ships contacting vanilla terrain. */
object ShipGroundCollisionDetector {
    private const val MIN_CLOSING_SPEED = 0.5
    private const val MAX_TRACKED_SHIPS = 8192
    private val previousBounds = LinkedHashMap<Long, Bounds>()
    private val contactingShips = HashSet<Long>()

    fun scan(world: ServerShipWorld, levels: Iterable<ServerLevel>) {
        val currentBounds = LinkedHashMap<Long, Bounds>()
        val currentContacts = HashSet<Long>()

        for (ship in world.loadedShips) {
            if (ship.isStatic) continue
            val bounds = bounds(ship) ?: continue
            currentBounds[ship.id] = bounds
            val level = levels.firstOrNull { belongsToLevel(ship, it) } ?: continue
            val sweptBounds = previousBounds[ship.id]?.union(bounds) ?: bounds
            val terrainShape = level.getBlockCollisions(null, sweptBounds.toMinecraft()).firstOrNull() ?: continue

            val velocity = Vector3d(ship.velocity)
            val speed = velocity.length()
            currentContacts += ship.id
            CollisionTelemetry.recordTerrainCandidate()
            if (speed < MIN_CLOSING_SPEED) {
                CollisionTelemetry.recordTerrainLowSpeedCandidate()
                continue
            }
            if (ship.id in contactingShips) {
                CollisionTelemetry.recordTerrainSuppressedCandidate()
                continue
            }

            val normal = velocity.negate()
            normal.normalize()
            val contactBounds = Bounds.fromMinecraft(terrainShape.bounds())
            ImpactQueue.offer(
                ImpactRecord(
                    dimensionId = ship.chunkClaimDimension,
                    bodyA = CollisionTarget.Body(ship.id),
                    bodyB = CollisionTarget.Ground,
                    contactPositionWorld = intersectionCenter(bounds, contactBounds),
                    normalWorld = normal,
                    separation = 0.0,
                    relativeVelocityWorld = velocity,
                    physicsTick = 0L
                )
            )
            CollisionTelemetry.recordApproximateTerrainImpact()
        }

        previousBounds.clear()
        previousBounds.putAll(currentBounds)
        while (previousBounds.size > MAX_TRACKED_SHIPS) previousBounds.remove(previousBounds.keys.first())
        contactingShips.clear()
        contactingShips.addAll(currentContacts)
    }

    private fun belongsToLevel(ship: LoadedServerShip, level: ServerLevel): Boolean =
        ship.chunkClaimDimension.endsWith(":${level.dimension().location()}")

    private fun bounds(ship: LoadedServerShip): Bounds? = try {
        val aabb = ship.javaClass.getMethod("getWorldAABB").invoke(ship)
        val type = aabb.javaClass
        fun number(name: String): Double = (type.getMethod(name).invoke(aabb) as Number).toDouble()
        Bounds(number("minX"), number("minY"), number("minZ"), number("maxX"), number("maxY"), number("maxZ"))
    } catch (_: Throwable) {
        null
    }

    private fun intersectionCenter(ship: Bounds, terrain: Bounds): Vector3d = Vector3d(
        (maxOf(ship.minX, terrain.minX) + minOf(ship.maxX, terrain.maxX)) * 0.5,
        (maxOf(ship.minY, terrain.minY) + minOf(ship.maxY, terrain.maxY)) * 0.5,
        (maxOf(ship.minZ, terrain.minZ) + minOf(ship.maxZ, terrain.maxZ)) * 0.5
    )

    private data class Bounds(
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double
    ) {
        fun union(other: Bounds) = Bounds(
            minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
            maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ)
        )

        fun toMinecraft() = AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.05)

        companion object {
            fun fromMinecraft(bounds: AABB) = Bounds(
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ
            )
        }
    }
}
