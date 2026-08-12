package org.valkyrienskies.vskinetic.forge

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.vskinetic.collision.CollisionTarget

data class MaterialProfile(
    val strength: Double,
    val toughness: Double,
    val canBreak: Boolean,
    val treatment: MaterialTreatment
)

enum class MaterialTreatment {
    NORMAL,
    NO_DAMAGE,
    PROTECTIVE
}

data class PlannedBlock(
    val target: CollisionTarget,
    val position: BlockPos,
    val expectedState: BlockState,
    val energyCost: Double,
    val material: MaterialProfile
)

data class DamagePlan(
    val dimensionId: DimensionId,
    val target: CollisionTarget,
    val energyBudget: Double,
    val blocks: List<PlannedBlock>
)
