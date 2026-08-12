package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.vskinetic.collision.CollisionTarget
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import org.valkyrienskies.vskinetic.collision.DebugColors
import org.valkyrienskies.vskinetic.collision.DebugMarkerStyle
import org.valkyrienskies.vskinetic.collision.DebugOverlay
import org.valkyrienskies.vskinetic.collision.ImpactQueue
import org.valkyrienskies.vskinetic.collision.ImpactRecord
import org.valkyrienskies.vskinetic.collision.ImpactPhase
import org.valkyrienskies.vskinetic.collision.ImpactSource

/** Server-tick approximation for dynamic ships contacting vanilla terrain. */
object ShipGroundCollisionDetector {
    private const val IMPULSE_MIN_SPEED = 2.0
    private const val STOP_SPEED = 2.0
    private const val CONTACT_INFLATE = 0.25
    private const val MAX_TRACKED_SHIPS = 8192
    private val previousBounds = LinkedHashMap<Long, Bounds>()
    private val previousVelocities = HashMap<Long, Vector3d>()
    private val contactingTerrain = HashSet<TerrainContactKey>()

    fun scan(world: ServerShipWorld, levels: Iterable<ServerLevel>) {
        val currentBounds = LinkedHashMap<Long, Bounds>()
        val currentVelocities = HashMap<Long, Vector3d>()
        val currentTerrainContacts = HashSet<TerrainContactKey>()

        for (ship in world.loadedShips) {
            if (ship.isStatic) continue
            val bounds = bounds(ship) ?: continue
            currentBounds[ship.id] = bounds
            val level = levels.firstOrNull { belongsToLevel(ship, it) } ?: continue
            val previous = previousBounds[ship.id]
            val swept = previous?.union(bounds) ?: bounds
            val velocity = Vector3d(ship.velocity)
            currentVelocities[ship.id] = Vector3d(velocity)
            val motion = previous?.let { Vector3d(bounds.center).sub(it.center) } ?: Vector3d()
            val previousVelocity = previousVelocities[ship.id]

            val searchMotion = listOfNotNull(previousVelocity?.let(::Vector3d), Vector3d(motion), Vector3d(velocity))
                .maxByOrNull { it.lengthSquared() } ?: Vector3d(0.0, -1.0, 0.0)
            val searchDirection = cardinalDirection(searchMotion)
            val terrainContact = findTerrainContact(level, bounds, swept, searchDirection) ?: continue
            CollisionTelemetry.recordTerrainCandidate()

            val toTerrain = Vector3d(terrainContact.normal).negate()
            val approachSpeed = listOfNotNull(previousVelocity?.let(::Vector3d), Vector3d(motion), Vector3d(velocity))
                .maxOfOrNull { it.dot(toTerrain) }?.coerceAtLeast(0.0) ?: 0.0
            val postClosing = velocity.dot(toTerrain).coerceAtLeast(0.0)
            val impactSpeed =
                kotlin.math.sqrt((approachSpeed * approachSpeed - postClosing * postClosing).coerceAtLeast(0.0))
                    DebugOverlay.record(
                        terrainContact.position,
                        "ground contact closing=${"%.1f".format(impactSpeed)} m/s",
                        DebugColors.TERRAIN_CONTACT,
                        toTerrain,
                        DebugMarkerStyle.POINT
            )
            if (impactSpeed < IMPULSE_MIN_SPEED) {
                    DebugOverlay.record(
                        terrainContact.position,
                        "reject: low speed (${"%.1f".format(impactSpeed)} m/s)",
                        DebugColors.LOW_SPEED,
                        toTerrain,
                        DebugMarkerStyle.POINT
                )
                CollisionTelemetry.recordTerrainLowSpeedCandidate()
                continue
            }
            val terrainKey = TerrainContactKey(ship.id, terrainContact.blockPos.asLong())
            currentTerrainContacts += terrainKey
            if (terrainKey in contactingTerrain) {
                CollisionTelemetry.recordTerrainSuppressedCandidate()
                continue
            }

            ImpactQueue.offer(
                ImpactRecord(
                    dimensionId = ship.chunkClaimDimension,
                    bodyA = CollisionTarget.Body(ship.id),
                    bodyB = CollisionTarget.Ground,
                    contactPositionWorld = terrainContact.position,
                    normalWorld = terrainContact.normal,
                    separation = 0.0,
                    relativeVelocityWorld = Vector3d(toTerrain).mul(impactSpeed),
                    physicsTick = 0L,
                    contactBlockPosition = terrainContact.blockPos.asLong(),
                    source = ImpactSource.APPROXIMATE,
                    phase = ImpactPhase.START
                )
            )
            CollisionTelemetry.recordApproximateTerrainImpact()
        }

        previousBounds.clear()
        previousBounds.putAll(currentBounds)
        while (previousBounds.size > MAX_TRACKED_SHIPS) previousBounds.remove(previousBounds.keys.first())
        previousVelocities.clear()
        previousVelocities.putAll(currentVelocities)
        contactingTerrain.clear()
        contactingTerrain.addAll(currentTerrainContacts)
    }

    private fun cardinalDirection(vector: Vector3d): Vector3d {
        val absX = kotlin.math.abs(vector.x)
        val absY = kotlin.math.abs(vector.y)
        val absZ = kotlin.math.abs(vector.z)
        return when {
            absX >= absY && absX >= absZ && absX > 1.0E-8 -> Vector3d(kotlin.math.sign(vector.x), 0.0, 0.0)
            absY >= absZ && absY > 1.0E-8 -> Vector3d(0.0, kotlin.math.sign(vector.y), 0.0)
            absZ > 1.0E-8 -> Vector3d(0.0, 0.0, kotlin.math.sign(vector.z))
            else -> Vector3d(0.0, -1.0, 0.0)
        }
    }

    private fun findTerrainContact(
        level: ServerLevel,
        current: Bounds,
        swept: Bounds,
        searchMotion: Vector3d
    ): TerrainContact? {
        val search = swept.inflate(CONTACT_INFLATE)
        val minX = kotlin.math.floor(search.minX).toInt()
        val minY = kotlin.math.floor(search.minY).toInt()
        val minZ = kotlin.math.floor(search.minZ).toInt()
        val maxX = kotlin.math.floor(search.maxX).toInt()
        val maxY = kotlin.math.floor(search.maxY).toInt()
        val maxZ = kotlin.math.floor(search.maxZ).toInt()
        var best: TerrainContact? = null
        var bestScore = Double.POSITIVE_INFINITY

        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val blockPos = BlockPos(x, y, z)
            val state = level.getBlockState(blockPos)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
            if (shape.isEmpty()) continue
            shape.forAllBoxes { minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ ->
                val box = Bounds(
                    x + minBoxX, y + minBoxY, z + minBoxZ,
                    x + maxBoxX, y + maxBoxY, z + maxBoxZ
                )
                val contact = facingContact(box, current, searchMotion) ?: return@forAllBoxes
                if (contact.score < bestScore) {
                    bestScore = contact.score
                    best = TerrainContact(blockPos, contact.position, contact.normal)
                }
            }
        }
        return best
    }

    /** Only accepts the terrain face directly in front of the ship's current movement. */
    private fun facingContact(box: Bounds, ship: Bounds, motion: Vector3d): FacingContact? {
        val ax = kotlin.math.abs(motion.x)
        val ay = kotlin.math.abs(motion.y)
        val az = kotlin.math.abs(motion.z)
        return when {
            ax >= ay && ax >= az -> xFaceContact(box, ship, motion.x)
            ay >= az -> yFaceContact(box, ship, motion.y)
            else -> zFaceContact(box, ship, motion.z)
        }
    }

    private fun xFaceContact(box: Bounds, ship: Bounds, motion: Double): FacingContact? {
        if (kotlin.math.abs(motion) <= 1.0E-8) return null
        val y = overlap(box.minY, box.maxY, ship.minY, ship.maxY) ?: return null
        val z = overlap(box.minZ, box.maxZ, ship.minZ, ship.maxZ) ?: return null
        val positive = motion > 0.0
        val gap = if (positive) box.minX - ship.maxX else ship.minX - box.maxX
        if (gap < -CONTACT_INFLATE || gap > CONTACT_INFLATE) return null
        val normal = if (positive) Vector3d(-1.0, 0.0, 0.0) else Vector3d(1.0, 0.0, 0.0)
        val closing = kotlin.math.abs(motion)
        return FacingContact(kotlin.math.abs(gap) * 1.0e3 - closing * 1.0e2 - y.length * z.length, normal, Vector3d(if (positive) box.minX else box.maxX, y.midpoint, z.midpoint))
    }

    private fun yFaceContact(box: Bounds, ship: Bounds, motion: Double): FacingContact? {
        if (kotlin.math.abs(motion) <= 1.0E-8) return null
        val x = overlap(box.minX, box.maxX, ship.minX, ship.maxX) ?: return null
        val z = overlap(box.minZ, box.maxZ, ship.minZ, ship.maxZ) ?: return null
        val positive = motion > 0.0
        val gap = if (positive) box.minY - ship.maxY else ship.minY - box.maxY
        if (gap < -CONTACT_INFLATE || gap > CONTACT_INFLATE) return null
        val normal = if (positive) Vector3d(0.0, -1.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
        val closing = kotlin.math.abs(motion)
        return FacingContact(kotlin.math.abs(gap) * 1.0e3 - closing * 1.0e2 - x.length * z.length, normal, Vector3d(x.midpoint, if (positive) box.minY else box.maxY, z.midpoint))
    }

    private fun zFaceContact(box: Bounds, ship: Bounds, motion: Double): FacingContact? {
        if (kotlin.math.abs(motion) <= 1.0E-8) return null
        val x = overlap(box.minX, box.maxX, ship.minX, ship.maxX) ?: return null
        val y = overlap(box.minY, box.maxY, ship.minY, ship.maxY) ?: return null
        val positive = motion > 0.0
        val gap = if (positive) box.minZ - ship.maxZ else ship.minZ - box.maxZ
        if (gap < -CONTACT_INFLATE || gap > CONTACT_INFLATE) return null
        val normal = if (positive) Vector3d(0.0, 0.0, -1.0) else Vector3d(0.0, 0.0, 1.0)
        val closing = kotlin.math.abs(motion)
        return FacingContact(kotlin.math.abs(gap) * 1.0e3 - closing * 1.0e2 - x.length * y.length, normal, Vector3d(x.midpoint, y.midpoint, if (positive) box.minZ else box.maxZ))
    }

    private fun overlap(firstMin: Double, firstMax: Double, secondMin: Double, secondMax: Double): Overlap? {
        val min = maxOf(firstMin, secondMin)
        val max = minOf(firstMax, secondMax)
        return if (max - min > 1.0E-6) Overlap(min, max) else null
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

    private data class Bounds(
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double
    ) {
        val center: Vector3d
            get() = Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)

        fun union(other: Bounds) = Bounds(
            minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
            maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ)
        )

        fun inflate(delta: Double) = Bounds(
            minX - delta, minY - delta, minZ - delta,
            maxX + delta, maxY + delta, maxZ + delta
        )

    }

    private data class TerrainContact(val blockPos: BlockPos, val position: Vector3d, val normal: Vector3d)
    private data class FacingContact(val score: Double, val normal: Vector3d, val position: Vector3d)
    private data class Overlap(val min: Double, val max: Double) {
        val length: Double get() = max - min
        val midpoint: Double get() = (min + max) * 0.5
    }
    private data class TerrainContactKey(val shipId: Long, val blockPos: Long)
}
