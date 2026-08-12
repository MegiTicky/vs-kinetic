package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.pow

object MaterialProfileResolver {
    private const val MAX_RESISTANCE = 3.6e7
    private val cache = HashMap<BlockState, MaterialProfile>()

    @Synchronized
    fun resolve(level: Level, pos: BlockPos, state: BlockState): MaterialProfile {
        return cache.getOrPut(state) {
            val resistance = state.block.getExplosionResistance().toDouble().coerceAtLeast(0.0)
            val strength = if (state.isAir || resistance >= MAX_RESISTANCE) {
                Double.POSITIVE_INFINITY
            } else {
                (kotlin.math.log2(1.0 + resistance).coerceAtLeast(0.1).pow(1.25))
                    .coerceIn(0.1, 1000.0)
            }
            val canBreak = !state.isAir && state.getDestroySpeed(level, pos) >= 0.0 && resistance < MAX_RESISTANCE
            MaterialProfile(
                strength = strength,
                toughness = strength,
                canBreak = canBreak,
                treatment = if (canBreak) MaterialTreatment.NORMAL else MaterialTreatment.NO_DAMAGE
            )
        }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
