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
import org.valkyrienskies.vskinetic.collision.ImpactPhase
import org.valkyrienskies.vskinetic.collision.ImpactQueue
import org.valkyrienskies.vskinetic.collision.ImpactRecord
import org.valkyrienskies.vskinetic.collision.ImpactSource

/** Server-tick approximation for dynamic ships contacting vanilla terrain. */
object ShipGroundCollisionDetector {
    private const val IMPULSE_MIN_SPEED = 2.0
    private const val CONTACT_INFLATE = 0.25
    // A side face must overlap the ship by more than solver/tick-position slop.
    // This prevents a floor that is slightly penetrating the ship from becoming a wall
    // when the ship is translated horizontally across it.
    private const val MIN_SIDE_OVERLAP = 0.25
    private const val SUPPORT_SURFACE_TOLERANCE = 0.25
    private const val MIN_NORMAL_ALIGNMENT = 0.5
    private const val REARM_CLEAR_TICKS = 2
    private const val MAX_TRACKED_SHIPS = 8192
    private const val CACHE_REFRESH_TICKS = 10L
    private const val MAX_CACHE_BLOCKS = 100_000
    private const val MAX_CACHE_BOXES = 200_000
    private const val MAX_ACTIVE_BOXES = 256
    private const val LEADING_SURFACE_DEPTH = 1.25
    private val previousBounds = LinkedHashMap<Long, Bounds>()
    private val previousMotion = HashMap<Long, MotionState>()
    private val previousGeometry = HashMap<Long, List<WorldBox>>()
    private val geometryCache = HashMap<Long, GeometryCache>()
    private val contactingTerrain = HashSet<TerrainContactKey>()
    private val terrainClearTicks = HashMap<TerrainContactKey, Int>()

    fun reset() {
        previousBounds.clear()
        previousMotion.clear()
        previousGeometry.clear()
        geometryCache.clear()
        contactingTerrain.clear()
        terrainClearTicks.clear()
    }

    fun scan(world: ServerShipWorld, levels: Iterable<ServerLevel>) {
        val tick = scanTick++
        val currentBounds = LinkedHashMap<Long, Bounds>()
        val currentMotion = HashMap<Long, MotionState>()
        val currentGeometry = HashMap<Long, List<WorldBox>>()
        val currentTerrainContacts = HashSet<TerrainContactKey>()

        for (ship in world.loadedShips) {
            if (ship.isStatic) continue
            val bounds = bounds(ship) ?: continue
            currentBounds[ship.id] = bounds
            val level = levels.firstOrNull { belongsToLevel(ship, it) } ?: continue
            val previous = previousBounds[ship.id]
            val swept = previous?.union(bounds) ?: bounds
            val currentState = MotionState(
                linearVelocity = Vector3d(ship.velocity),
                angularVelocity = Vector3d(ship.omega),
                centerOfMass = Vector3d(ship.transform.positionInWorld)
            )
            currentMotion[ship.id] = currentState
            val boundsMotion = previous?.let { Vector3d(bounds.center).sub(it.center) } ?: Vector3d()

            val searchMotion = listOf(
                previousMotion[ship.id]?.linearVelocity,
                currentState.linearVelocity,
                boundsMotion
            ).filterNotNull().maxByOrNull { it.lengthSquared() } ?: Vector3d(0.0, -1.0, 0.0)
            val searchDirection = cardinalDirection(searchMotion)
            val geometry = geometry(ship, level, tick)
            val worldGeometry = geometry?.boxes?.let { leadingBoxes(it, ship, searchDirection) }
            if (!worldGeometry.isNullOrEmpty()) currentGeometry[ship.id] = worldGeometry
            val terrainContact = if (worldGeometry.isNullOrEmpty()) {
                findTerrainContact(level, bounds, swept, searchMotion)
            } else {
                findTerrainContact(level, worldGeometry, previousGeometry[ship.id], searchMotion)
            } ?: continue
            CollisionTelemetry.recordTerrainCandidate()
            val terrainKey = TerrainContactKey(ship.id, terrainContact.blockPos.asLong(), face(terrainContact.normal))
            currentTerrainContacts += terrainKey

            // The previous physics state is the best available pre-solver velocity at the contact point.
            val approachVelocity = (previousMotion[ship.id] ?: currentState).velocityAt(terrainContact.position)
            val impactSpeed = (-approachVelocity.dot(terrainContact.normal)).coerceAtLeast(0.0)
            val alignment = terrainContact.normalAlignment(approachVelocity)
            if (impactSpeed < IMPULSE_MIN_SPEED || alignment < MIN_NORMAL_ALIGNMENT) {
                CollisionTelemetry.recordTerrainLowSpeedCandidate()
                continue
            }
            if (terrainKey in contactingTerrain) {
                CollisionTelemetry.recordTerrainSuppressedCandidate()
                continue
            }

            DebugOverlay.record(
                terrainContact.position,
                "approx terrain ${if (worldGeometry.isNullOrEmpty()) "AABB" else "shape"} " +
                    "n=${"%.1f".format(impactSpeed)} align=${"%.2f".format(alignment)}",
                DebugColors.TERRAIN_CONTACT,
                terrainContact.normal,
                DebugMarkerStyle.POINT
            )
            DebugOverlay.record(
                terrainContact.position,
                "approx terrain velocity=${"%.1f".format(approachVelocity.length())} m/s",
                DebugColors.LOW_SPEED,
                Vector3d(approachVelocity).normalize(),
                DebugMarkerStyle.POINT
            )
            ImpactQueue.offer(
                ImpactRecord(
                    dimensionId = ship.chunkClaimDimension,
                    bodyA = CollisionTarget.Body(ship.id),
                    bodyB = CollisionTarget.Ground,
                    contactPositionWorld = terrainContact.position,
                    normalWorld = terrainContact.normal,
                    separation = 0.0,
                    relativeVelocityWorld = approachVelocity,
                    physicsTick = scanTick,
                    contactBlockPosition = terrainContact.blockPos.asLong(),
                    source = ImpactSource.APPROXIMATE,
                    phase = ImpactPhase.START
                )
            )
            CollisionTelemetry.recordApproximateTerrainImpact()
            contactingTerrain += terrainKey
        }

        previousBounds.clear()
        previousBounds.putAll(currentBounds)
        while (previousBounds.size > MAX_TRACKED_SHIPS) previousBounds.remove(previousBounds.keys.first())
        previousMotion.clear()
        previousMotion.putAll(currentMotion)
        previousGeometry.clear()
        previousGeometry.putAll(currentGeometry)
        previousGeometry.keys.removeIf { it !in currentBounds }
        geometryCache.keys.removeIf { it !in currentBounds }
        updateContactEpisodes(currentTerrainContacts)
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

    private fun findTerrainContact(
        level: ServerLevel,
        shipBoxes: List<WorldBox>,
        previousBoxes: List<WorldBox>?,
        searchMotion: Vector3d
    ): TerrainContact? {
        var best: TerrainContact? = null
        var bestScore = Double.POSITIVE_INFINITY
        val searchedBlocks = HashSet<Long>()
        for ((index, shipBox) in shipBoxes.withIndex()) {
            val query = shipBox.bounds.union(previousBoxes?.getOrNull(index)?.bounds ?: shipBox.bounds)
                .inflate(CONTACT_INFLATE)
            val minX = kotlin.math.floor(query.minX).toInt()
            val minY = kotlin.math.floor(query.minY).toInt()
            val minZ = kotlin.math.floor(query.minZ).toInt()
            val maxX = kotlin.math.floor(query.maxX).toInt()
            val maxY = kotlin.math.floor(query.maxY).toInt()
            val maxZ = kotlin.math.floor(query.maxZ).toInt()
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
                val blockPos = BlockPos(x, y, z)
                if (!searchedBlocks.add(blockPos.asLong())) continue
                val state = level.getBlockState(blockPos)
                if (state.isAir) continue
                val shape = state.getCollisionShape(level, blockPos, CollisionContext.empty())
                if (shape.isEmpty()) continue
                shape.forAllBoxes { minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ ->
                    val terrainBox = Bounds(
                        x + minBoxX, y + minBoxY, z + minBoxZ,
                        x + maxBoxX, y + maxBoxY, z + maxBoxZ
                    )
                    val contact = facingContact(terrainBox, shipBox.bounds, searchMotion) ?: return@forAllBoxes
                    if (contact.score < bestScore) {
                        bestScore = contact.score
                        best = TerrainContact(blockPos, contact.position, contact.normal)
                    }
                }
            }
        }
        return best
    }

    private fun geometry(ship: LoadedServerShip, level: ServerLevel, tick: Long): GeometryCache? {
        val bounds = ship.shipAABB ?: return null
        val signature = listOf(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())
        val existing = geometryCache[ship.id]
        if (existing != null && existing.signature == signature && tick - existing.builtTick < CACHE_REFRESH_TICKS) {
            return existing
        }
        var blockCount = 0
        val boxes = ArrayList<LocalBox>()
        for (x in bounds.minX() until bounds.maxX()) {
            for (y in bounds.minY() until bounds.maxY()) {
                for (z in bounds.minZ() until bounds.maxZ()) {
                    if (++blockCount > MAX_CACHE_BLOCKS) return null
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    if (state.isAir) continue
                    val shape = state.getCollisionShape(level, pos, CollisionContext.empty())
                    if (shape.isEmpty()) continue
                    shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                        if (boxes.size < MAX_CACHE_BOXES) {
                            boxes += LocalBox(
                                Bounds(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ),
                                pos.immutable()
                            )
                        }
                    }
                    if (boxes.size >= MAX_CACHE_BOXES) return null
                }
            }
        }
        return GeometryCache(signature, tick, boxes).also { geometryCache[ship.id] = it }
    }

    private fun transform(box: LocalBox, ship: LoadedServerShip): WorldBox {
        val min = box.bounds
        val corners = arrayOf(
            Vector3d(min.minX, min.minY, min.minZ), Vector3d(min.minX, min.minY, min.maxZ),
            Vector3d(min.minX, min.maxY, min.minZ), Vector3d(min.minX, min.maxY, min.maxZ),
            Vector3d(min.maxX, min.minY, min.minZ), Vector3d(min.maxX, min.minY, min.maxZ),
            Vector3d(min.maxX, min.maxY, min.minZ), Vector3d(min.maxX, min.maxY, min.maxZ)
        )
        corners.forEach { ship.shipToWorld.transformPosition(it) }
        return WorldBox(
            Bounds(
                corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z },
                corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }
            ),
            box.blockPos
        )
    }

    /** Restricts the narrow phase to collision boxes on the moving side of the ship. */
    private fun leadingBoxes(
        boxes: List<LocalBox>,
        ship: LoadedServerShip,
        worldDirection: Vector3d
    ): List<WorldBox>? {
        val localDirection = ship.worldToShip.transformDirection(Vector3d(worldDirection))
        if (localDirection.lengthSquared() <= 1.0E-8) return null
        localDirection.normalize()
        var leadingProjection = Double.NEGATIVE_INFINITY
        for (box in boxes) leadingProjection = maxOf(leadingProjection, box.bounds.maxProjection(localDirection))
        val selected = ArrayList<WorldBox>()
        for (box in boxes) {
            if (box.bounds.maxProjection(localDirection) < leadingProjection - LEADING_SURFACE_DEPTH) continue
            if (selected.size >= MAX_ACTIVE_BOXES) return null
            selected += transform(box, ship)
        }
        return selected
    }

    private var scanTick = 0L

    /** Selects a geometrically valid face; movement is only used to select the approached side. */
    private fun facingContact(box: Bounds, ship: Bounds, motion: Vector3d): FacingContact? {
        return listOfNotNull(
            xFaceContact(box, ship, motion.x),
            yFaceContact(box, ship, motion.y),
            zFaceContact(box, ship, motion.z)
        ).minByOrNull { it.score }
    }

    private fun xFaceContact(box: Bounds, ship: Bounds, motion: Double): FacingContact? {
        if (kotlin.math.abs(motion) <= 1.0E-8) return null
        // A block whose top is at the ship's underside is support, not a horizontal wall.
        if (box.maxY <= ship.minY + SUPPORT_SURFACE_TOLERANCE) return null
        val y = overlap(box.minY, box.maxY, ship.minY, ship.maxY) ?: return null
        val z = overlap(box.minZ, box.maxZ, ship.minZ, ship.maxZ) ?: return null
        if (y.length < MIN_SIDE_OVERLAP || z.length < MIN_SIDE_OVERLAP) return null
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
        if (x.length < MIN_SIDE_OVERLAP || z.length < MIN_SIDE_OVERLAP) return null
        val positive = motion > 0.0
        val gap = if (positive) box.minY - ship.maxY else ship.minY - box.maxY
        if (gap < -CONTACT_INFLATE || gap > CONTACT_INFLATE) return null
        val normal = if (positive) Vector3d(0.0, -1.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
        val closing = kotlin.math.abs(motion)
        return FacingContact(kotlin.math.abs(gap) * 1.0e3 - closing * 1.0e2 - x.length * z.length, normal, Vector3d(x.midpoint, if (positive) box.minY else box.maxY, z.midpoint))
    }

    private fun zFaceContact(box: Bounds, ship: Bounds, motion: Double): FacingContact? {
        if (kotlin.math.abs(motion) <= 1.0E-8) return null
        // A block whose top is at the ship's underside is support, not a horizontal wall.
        if (box.maxY <= ship.minY + SUPPORT_SURFACE_TOLERANCE) return null
        val x = overlap(box.minX, box.maxX, ship.minX, ship.maxX) ?: return null
        val y = overlap(box.minY, box.maxY, ship.minY, ship.maxY) ?: return null
        if (x.length < MIN_SIDE_OVERLAP || y.length < MIN_SIDE_OVERLAP) return null
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

    private fun TerrainContact.normalAlignment(velocity: Vector3d): Double {
        val speed = velocity.length()
        return if (speed <= 1.0E-8) 0.0 else (-velocity.dot(normal) / speed).coerceIn(0.0, 1.0)
    }

    private fun updateContactEpisodes(current: Set<TerrainContactKey>) {
        val next = HashSet<TerrainContactKey>()
        for (key in contactingTerrain) {
            if (key in current) {
                terrainClearTicks.remove(key)
                next += key
                continue
            }
            val clearTicks = (terrainClearTicks[key] ?: 0) + 1
            if (clearTicks < REARM_CLEAR_TICKS) {
                terrainClearTicks[key] = clearTicks
                next += key
            } else {
                terrainClearTicks.remove(key)
            }
        }
        contactingTerrain.clear()
        contactingTerrain.addAll(next)
    }

    private fun face(normal: Vector3d): TerrainFace = when {
        kotlin.math.abs(normal.x) > 0.5 -> if (normal.x > 0.0) TerrainFace.POS_X else TerrainFace.NEG_X
        kotlin.math.abs(normal.y) > 0.5 -> if (normal.y > 0.0) TerrainFace.POS_Y else TerrainFace.NEG_Y
        normal.z > 0.0 -> TerrainFace.POS_Z
        else -> TerrainFace.NEG_Z
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

        fun maxProjection(direction: Vector3d): Double {
            val x = if (direction.x >= 0.0) maxX else minX
            val y = if (direction.y >= 0.0) maxY else minY
            val z = if (direction.z >= 0.0) maxZ else minZ
            return x * direction.x + y * direction.y + z * direction.z
        }

    }

    private data class TerrainContact(val blockPos: BlockPos, val position: Vector3d, val normal: Vector3d)
    private data class FacingContact(val score: Double, val normal: Vector3d, val position: Vector3d)
    private data class MotionState(
        val linearVelocity: Vector3d,
        val angularVelocity: Vector3d,
        val centerOfMass: Vector3d
    ) {
        fun velocityAt(position: Vector3d): Vector3d = Vector3d(linearVelocity).add(
            Vector3d(angularVelocity).cross(Vector3d(position).sub(centerOfMass))
        )
    }
    private data class Overlap(val min: Double, val max: Double) {
        val length: Double get() = max - min
        val midpoint: Double get() = (min + max) * 0.5
    }
    private enum class TerrainFace { POS_X, NEG_X, POS_Y, NEG_Y, POS_Z, NEG_Z }
    private data class TerrainContactKey(val shipId: Long, val blockPos: Long, val face: TerrainFace)

    private data class LocalBox(val bounds: Bounds, val blockPos: BlockPos)
    private data class WorldBox(val bounds: Bounds, val blockPos: BlockPos)
    private data class GeometryCache(val signature: List<Int>, val builtTick: Long, val boxes: List<LocalBox>)

}
