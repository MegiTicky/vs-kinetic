package org.valkyrienskies.vskinetic.collision

/**
 * The Forge runtime uses ForgeShipPairCollisionDetector so it can read actual Minecraft
 * collision shapes. This empty compatibility holder avoids changing the common module API.
 */
@Deprecated("Forge uses ForgeShipPairCollisionDetector for shape-aware ship pair contacts.")
object ShipPairCollisionDetector
