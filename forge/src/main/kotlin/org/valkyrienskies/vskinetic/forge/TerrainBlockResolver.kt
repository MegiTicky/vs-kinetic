package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.shapes.CollisionContext
import org.joml.Vector3d

/** Resolves a physics contact point to the terrain collision shape that was struck. */
object TerrainBlockResolver {
    private const val SEARCH_RADIUS = 2
    private const val CONTACT_EPSILON = 0.2

    fun resolve(level: ServerLevel, point: Vector3d, incomingDirection: Vector3d): BlockPos? {
        val direction = if (incomingDirection.lengthSquared() > 1.0E-8) {
            Vector3d(incomingDirection).normalize()
        } else {
            Vector3d(0.0, -1.0, 0.0)
        }
        // Probe just inside the target first. This disambiguates a wall/floor corner.
        val probe = Vector3d(point).fma(CONTACT_EPSILON, direction)
        val probePos = BlockPos.containing(probe.x, probe.y, probe.z)
        val probeState = level.getBlockState(probePos)
        if (!probeState.isAir && !probeState.getCollisionShape(level, probePos, CollisionContext.empty()).isEmpty()) {
            return probePos
        }
        val base = BlockPos.containing(point.x, point.y, point.z)
        var best: BlockPos? = null
        var bestScore = Double.POSITIVE_INFINITY

        for (x in base.x - SEARCH_RADIUS..base.x + SEARCH_RADIUS) {
            for (y in base.y - SEARCH_RADIUS..base.y + SEARCH_RADIUS) {
                for (z in base.z - SEARCH_RADIUS..base.z + SEARCH_RADIUS) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    if (state.isAir) continue
                    val shape = state.getCollisionShape(level, pos, CollisionContext.empty())
                    if (shape.isEmpty()) continue
                    shape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                        val box = Box(
                            x + minX, y + minY, z + minZ,
                            x + maxX, y + maxY, z + maxZ
                        )
                        val distanceSquared = box.distanceSquared(point)
                        if (distanceSquared > CONTACT_EPSILON * CONTACT_EPSILON) return@forAllBoxes
                        val relative = Vector3d(box.center).sub(point)
                        val forward = relative.dot(direction)
                        val behindPenalty = if (forward < -CONTACT_EPSILON) 1000.0 else 0.0
                        val score = distanceSquared * 1000.0 + behindPenalty + kotlin.math.abs(forward) * 0.01
                        if (score < bestScore) {
                            bestScore = score
                            best = pos
                        }
                    }
                }
            }
        }
        return best
    }

    private data class Box(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double
    ) {
        val center: Vector3d
            get() = Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)

        fun distanceSquared(point: Vector3d): Double {
            val dx = when {
                point.x < minX -> minX - point.x
                point.x > maxX -> point.x - maxX
                else -> 0.0
            }
            val dy = when {
                point.y < minY -> minY - point.y
                point.y > maxY -> point.y - maxY
                else -> 0.0
            }
            val dz = when {
                point.z < minZ -> minZ - point.z
                point.z > maxZ -> point.z - maxZ
                else -> 0.0
            }
            return dx * dx + dy * dy + dz * dz
        }
    }
}
