package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.vskinetic.collision.CollisionTarget
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import org.valkyrienskies.vskinetic.collision.DebugColors
import org.valkyrienskies.vskinetic.collision.DebugOverlay
import org.valkyrienskies.vskinetic.collision.ImpactPhase
import org.valkyrienskies.vskinetic.collision.ImpactQueue
import org.valkyrienskies.vskinetic.collision.ImpactRecord
import org.valkyrienskies.vskinetic.collision.ImpactSource

/** Forge-only ship pair fallback using actual shipyard voxel collision boxes. */
@OptIn(VsBeta::class)
object ForgeShipPairCollisionDetector {
    private const val MIN_CLOSING_SPEED = 0.5
    private const val CACHE_REFRESH_TICKS = 10L
    private const val MAX_CACHE_BLOCKS = 100_000
    private const val MAX_CACHE_BOXES = 200_000
    private const val MAX_PAIR_BOXES = 512
    private const val EPSILON = 1.0E-5
    private val cache = HashMap<Long, GeometryCache>()
    private val overlappingPairs = LinkedHashMap<PairKey, Double>()
    private val previousBounds = HashMap<Long, Box3d>()
    private val previousMotion = HashMap<Long, MotionState>()
    private val previousGeometry = HashMap<Long, List<WorldBox>>()
    private var scanTick = 0L

    fun scan(world: ServerShipWorld, levels: Iterable<ServerLevel>) {
        val tick = scanTick++
        val ships = world.loadedShips.toList()
        val currentPairs = LinkedHashMap<PairKey, Double>()
        val currentBounds = HashMap<Long, Box3d>()
        val currentMotion = HashMap<Long, MotionState>()
        val currentGeometry = HashMap<Long, List<WorldBox>>()

        for (ship in ships) {
            if (ship.isStatic && ships.none { it.id != ship.id && !it.isStatic }) continue
            val level = levels.firstOrNull { belongsToLevel(ship, it) } ?: continue
            val geometry = geometry(ship, level, tick) ?: continue
            currentGeometry[ship.id] = geometry.boxes.map { transform(it, ship) }
        }

        for (ship in ships) {
            worldBounds(ship)?.let { currentBounds[ship.id] = it }
            currentMotion[ship.id] = MotionState(
                linearVelocity = Vector3d(ship.velocity),
                angularVelocity = Vector3d(ship.omega),
                centerOfMass = Vector3d(ship.transform.positionInWorld)
            )
        }

        for (index in ships.indices) {
            for (otherIndex in index + 1 until ships.size) {
                val first = ships[index]
                val second = ships[otherIndex]
                if (first.isStatic && second.isStatic) continue
                if (first.chunkClaimDimension != second.chunkClaimDimension) continue
                val shipA = if (!first.isStatic) first else second
                val shipB = if (!first.isStatic) second else first
                val boundsA = currentBounds[shipA.id] ?: continue
                val boundsB = currentBounds[shipB.id] ?: continue
                val sweptA = previousBounds[shipA.id]?.union(boundsA) ?: boundsA
                val sweptB = previousBounds[shipB.id]?.union(boundsB) ?: boundsB
                if (!overlaps(sweptA, sweptB)) continue
                val boxesA = currentGeometry[shipA.id] ?: continue
                val boxesB = currentGeometry[shipB.id] ?: continue
                if (boxesA.isEmpty() || boxesB.isEmpty()) continue

                val currentContact = bestContact(boxesA, boxesB)
                val sweptContact = bestSweptContact(
                    boxesA, previousGeometry[shipA.id], boxesB, previousGeometry[shipB.id]
                )
                val contact = listOfNotNull(currentContact, sweptContact).maxByOrNull { candidate ->
                    val motion = if (candidate.swept) {
                        velocityAt(previousMotion[shipA.id] ?: currentMotion.getValue(shipA.id), candidate.position)
                            .sub(velocityAt(previousMotion[shipB.id] ?: currentMotion.getValue(shipB.id), candidate.position))
                    } else {
                        velocityAt(currentMotion.getValue(shipA.id), candidate.position)
                            .sub(velocityAt(currentMotion.getValue(shipB.id), candidate.position))
                    }
                    -motion.dot(candidate.normal)
                } ?: continue
                val key = PairKey.of(shipA.id, shipB.id)
                val relativeVelocity = if (contact.swept) {
                    velocityAt(previousMotion[shipA.id] ?: currentMotion.getValue(shipA.id), contact.position)
                        .sub(velocityAt(previousMotion[shipB.id] ?: currentMotion.getValue(shipB.id), contact.position))
                } else {
                    velocityAt(currentMotion.getValue(shipA.id), contact.position)
                        .sub(velocityAt(currentMotion.getValue(shipB.id), contact.position))
                }
                val closingSpeed = -relativeVelocity.dot(contact.normal)
                currentPairs[key] = closingSpeed
                CollisionTelemetry.recordOverlapCandidate()
                if (closingSpeed < MIN_CLOSING_SPEED) {
                    CollisionTelemetry.recordLowSpeedCandidate()
                    continue
                }
                if (overlappingPairs[key]?.let { it >= MIN_CLOSING_SPEED } == true) {
                    CollisionTelemetry.recordSuppressedCandidate()
                    continue
                }

                DebugOverlay.record(
                    contact.position,
                    "pair shape closing=${"%.1f".format(closingSpeed)} m/s",
                    DebugColors.PAIR_CONTACT,
                    contact.normal
                )
                CollisionTelemetry.recordApproximateImpact()
                ImpactQueue.offer(
                    ImpactRecord(
                        dimensionId = shipA.chunkClaimDimension,
                        bodyA = CollisionTarget.Body(shipA.id),
                        bodyB = CollisionTarget.Body(shipB.id),
                        contactPositionWorld = contact.position,
                        normalWorld = contact.normal,
                        separation = -contact.penetration,
                        relativeVelocityWorld = relativeVelocity,
                        physicsTick = tick,
                        source = ImpactSource.APPROXIMATE,
                        phase = ImpactPhase.START
                    )
                )
            }
        }

        overlappingPairs.clear()
        overlappingPairs.putAll(currentPairs)
        previousBounds.clear()
        previousBounds.putAll(currentBounds)
        previousMotion.clear()
        previousMotion.putAll(currentMotion)
        previousGeometry.clear()
        previousGeometry.putAll(currentGeometry)
        cache.keys.removeIf { id -> ships.none { it.id == id } }
    }

    private fun geometry(ship: LoadedServerShip, level: ServerLevel, tick: Long): GeometryCache? {
        val aabb = ship.shipAABB ?: return null
        val signature = listOf(aabb.minX(), aabb.minY(), aabb.minZ(), aabb.maxX(), aabb.maxY(), aabb.maxZ())
        cache[ship.id]?.let { if (it.signature == signature && tick - it.builtTick < CACHE_REFRESH_TICKS) return it }
        val boxes = ArrayList<LocalBox>()
        var blocks = 0
        for (x in aabb.minX() until aabb.maxX()) for (y in aabb.minY() until aabb.maxY()) for (z in aabb.minZ() until aabb.maxZ()) {
            if (++blocks > MAX_CACHE_BLOCKS) return null
            val pos = BlockPos(x, y, z)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            val shape = state.getCollisionShape(level, pos, CollisionContext.empty())
            if (shape.isEmpty()) continue
            shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                if (boxes.size < MAX_CACHE_BOXES) {
                    boxes += LocalBox(
                        Box3d(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ),
                        pos.immutable()
                    )
                }
            }
            if (boxes.size >= MAX_CACHE_BOXES) return null
        }
        return GeometryCache(signature, tick, boxes).also { cache[ship.id] = it }
    }

    private fun transform(box: LocalBox, ship: LoadedServerShip): WorldBox {
        val b = box.box
        val corners = arrayOf(
            Vector3d(b.minX, b.minY, b.minZ), Vector3d(b.minX, b.minY, b.maxZ),
            Vector3d(b.minX, b.maxY, b.minZ), Vector3d(b.minX, b.maxY, b.maxZ),
            Vector3d(b.maxX, b.minY, b.minZ), Vector3d(b.maxX, b.minY, b.maxZ),
            Vector3d(b.maxX, b.maxY, b.minZ), Vector3d(b.maxX, b.maxY, b.maxZ)
        )
        corners.forEach { ship.shipToWorld.transformPosition(it) }
        val center = ship.shipToWorld.transformPosition(Vector3d(b.center))
        val axes = arrayOf(
            ship.shipToWorld.transformDirection(Vector3d(1.0, 0.0, 0.0)).normalize(),
            ship.shipToWorld.transformDirection(Vector3d(0.0, 1.0, 0.0)).normalize(),
            ship.shipToWorld.transformDirection(Vector3d(0.0, 0.0, 1.0)).normalize()
        )
        return WorldBox(
            center, axes,
            doubleArrayOf((b.maxX - b.minX) * 0.5, (b.maxY - b.minY) * 0.5, (b.maxZ - b.minZ) * 0.5),
            Box3d(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }, corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }),
            box.blockPos
        )
    }

    private fun bestContact(a: List<WorldBox>, b: List<WorldBox>): Contact? {
        val boundsA = bounds(a) ?: return null
        val boundsB = bounds(b) ?: return null
        val overlap = boundsA.intersection(boundsB) ?: return null
        val candidatesA = a.filter { it.aabb.isValid() && overlaps(it.aabb, overlap) }
        val candidatesB = b.filter { it.aabb.isValid() && overlaps(it.aabb, overlap) }
        if (candidatesA.size > MAX_PAIR_BOXES || candidatesB.size > MAX_PAIR_BOXES) return null
        var best: Contact? = null
        for (first in candidatesA) for (second in candidatesB) {
            if (!overlaps(first.aabb, second.aabb)) continue
            val contact = obbContact(first, second) ?: continue
            if (best == null || contact.penetration < best.penetration) best = contact
        }
        return best
    }

    private fun bestSweptContact(
        currentA: List<WorldBox>, previousA: List<WorldBox>?,
        currentB: List<WorldBox>, previousB: List<WorldBox>?
    ): Contact? {
        var best: Contact? = null
        for ((indexA, boxA) in currentA.withIndex()) {
            val oldA = previousA?.getOrNull(indexA) ?: boxA
            val sweepA = SweepBox(oldA.aabb, boxA.aabb)
            for ((indexB, boxB) in currentB.withIndex()) {
                val oldB = previousB?.getOrNull(indexB) ?: boxB
                val contact = sweptAabbContact(sweepA, SweepBox(oldB.aabb, boxB.aabb)) ?: continue
                if (best == null || contact.penetration < best.penetration) best = contact
            }
        }
        return best
    }

    private fun sweptAabbContact(a: SweepBox, b: SweepBox): Contact? {
        val startA = a.start.center
        val startB = b.start.center
        val delta = Vector3d(startA).sub(startB)
        val relativeMotion = Vector3d(a.end.center).sub(a.start.center)
            .sub(Vector3d(b.end.center).sub(b.start.center))
        val halfA = a.start.halfExtents().max(a.end.halfExtents())
        val halfB = b.start.halfExtents().max(b.end.halfExtents())
        val extent = Vector3d(halfA).add(halfB)
        var enter = 0.0
        var exit = 1.0
        var normal = Vector3d()
        for (axis in 0..2) {
            val position = delta[axis]
            val motion = relativeMotion[axis]
            val limit = extent[axis]
            if (kotlin.math.abs(motion) <= EPSILON) {
                if (kotlin.math.abs(position) > limit) return null
                continue
            }
            var near = (-limit - position) / motion
            var far = (limit - position) / motion
            if (near > far) {
                val swap = near
                near = far
                far = swap
            }
            if (near > enter) {
                enter = near
                normal = Vector3d()
                val sign = if (position + motion * enter >= 0.0) 1.0 else -1.0
                when (axis) {
                    0 -> normal.x = sign
                    1 -> normal.y = sign
                    else -> normal.z = sign
                }
            }
            exit = minOf(exit, far)
            if (enter > exit || exit < 0.0 || enter > 1.0) return null
        }
        val time = enter.coerceIn(0.0, 1.0)
        val centerA = interpolate(a.start.center, a.end.center, time)
        val centerB = interpolate(b.start.center, b.end.center, time)
        if (Vector3d(centerA).sub(centerB).dot(normal) < 0.0) normal.negate()
        val pointA = Vector3d(centerA).fma(-normal.x * halfA.x, Vector3d(1.0, 0.0, 0.0))
            .fma(-normal.y * halfA.y, Vector3d(0.0, 1.0, 0.0))
            .fma(-normal.z * halfA.z, Vector3d(0.0, 0.0, 1.0))
        val pointB = Vector3d(centerB).fma(normal.x * halfB.x, Vector3d(1.0, 0.0, 0.0))
            .fma(normal.y * halfB.y, Vector3d(0.0, 1.0, 0.0))
            .fma(normal.z * halfB.z, Vector3d(0.0, 0.0, 1.0))
        return Contact(Vector3d(pointA).add(pointB).mul(0.5), normal, 0.0, true)
    }

    private fun interpolate(start: Vector3d, end: Vector3d, time: Double): Vector3d =
        Vector3d(start).lerp(end, time)

    private fun obbContact(a: WorldBox, b: WorldBox): Contact? {
        val axes = ArrayList<Vector3d>(15)
        axes += a.axes
        axes += b.axes
        for (first in a.axes) for (second in b.axes) {
            val cross = Vector3d(first).cross(second)
            if (cross.lengthSquared() > EPSILON) axes += cross.normalize()
        }
        val delta = Vector3d(a.center).sub(b.center)
        var minimum = Double.POSITIVE_INFINITY
        var normal: Vector3d? = null
        for (axis in axes) {
            val ra = projectionRadius(a, axis)
            val rb = projectionRadius(b, axis)
            val overlap = ra + rb - kotlin.math.abs(delta.dot(axis))
            if (overlap <= 0.0) return null
            if (overlap < minimum) {
                minimum = overlap
                normal = Vector3d(axis)
                if (delta.dot(normal) < 0.0) normal.negate()
            }
        }
        val n = normal ?: return null
        val pointA = support(a, Vector3d(n).negate())
        val pointB = support(b, n)
        return Contact(Vector3d(pointA).add(pointB).mul(0.5), n, minimum)
    }

    private fun projectionRadius(box: WorldBox, axis: Vector3d): Double =
        kotlin.math.abs(axis.dot(box.axes[0])) * box.halfExtents[0] +
            kotlin.math.abs(axis.dot(box.axes[1])) * box.halfExtents[1] +
            kotlin.math.abs(axis.dot(box.axes[2])) * box.halfExtents[2]

    private fun support(box: WorldBox, direction: Vector3d): Vector3d {
        val result = Vector3d(box.center)
        for (index in 0..2) {
            val distance = if (direction.dot(box.axes[index]) >= 0.0) box.halfExtents[index] else -box.halfExtents[index]
            result.fma(distance, box.axes[index])
        }
        return result
    }

    private fun velocityAt(motion: MotionState, position: Vector3d): Vector3d =
        Vector3d(motion.linearVelocity).add(
            Vector3d(motion.angularVelocity).cross(Vector3d(position).sub(motion.centerOfMass))
        )

    private fun bounds(boxes: List<WorldBox>): Box3d? = boxes.map { it.aabb }.reduceOrNull { first, second -> first.union(second) }

    private fun overlaps(a: Box3d, b: Box3d): Boolean =
        a.maxX > b.minX && a.minX < b.maxX && a.maxY > b.minY && a.minY < b.maxY && a.maxZ > b.minZ && a.minZ < b.maxZ

    private fun worldBounds(ship: LoadedServerShip): Box3d? = try {
        val aabb = ship.javaClass.getMethod("getWorldAABB").invoke(ship)
        val type = aabb.javaClass
        fun coordinate(name: String) = (type.getMethod(name).invoke(aabb) as Number).toDouble()
        Box3d(
            coordinate("minX"), coordinate("minY"), coordinate("minZ"),
            coordinate("maxX"), coordinate("maxY"), coordinate("maxZ")
        )
    } catch (_: ReflectiveOperationException) {
        null
    }

    private fun belongsToLevel(ship: LoadedServerShip, level: ServerLevel): Boolean =
        ship.chunkClaimDimension.endsWith(":${level.dimension().location()}")

    private data class LocalBox(val box: Box3d, val blockPos: BlockPos)
    private data class GeometryCache(val signature: List<Int>, val builtTick: Long, val boxes: List<LocalBox>)
    private data class WorldBox(val center: Vector3d, val axes: Array<Vector3d>, val halfExtents: DoubleArray, val aabb: Box3d, val blockPos: BlockPos)
    private data class Contact(val position: Vector3d, val normal: Vector3d, val penetration: Double, val swept: Boolean = false)
    private data class SweepBox(val start: Box3d, val end: Box3d)
    private data class MotionState(
        val linearVelocity: Vector3d,
        val angularVelocity: Vector3d,
        val centerOfMass: Vector3d
    )
    private data class PairKey(val low: Long, val high: Long) { companion object { fun of(a: Long, b: Long) = if (a < b) PairKey(a, b) else PairKey(b, a) } }

    private data class Box3d(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
        val center: Vector3d get() = Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
        fun halfExtents() = Vector3d((maxX - minX) * 0.5, (maxY - minY) * 0.5, (maxZ - minZ) * 0.5)
        fun isValid() = maxX > minX && maxY > minY && maxZ > minZ
        fun union(other: Box3d) = Box3d(minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ), maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ))
        fun intersection(other: Box3d): Box3d? = Box3d(
            maxOf(minX, other.minX), maxOf(minY, other.minY), maxOf(minZ, other.minZ),
            minOf(maxX, other.maxX), minOf(maxY, other.maxY), minOf(maxZ, other.maxZ)
        ).takeIf { it.isValid() }
    }
}
