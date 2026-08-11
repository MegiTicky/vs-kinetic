package org.valkyrienskies.vskinetic.collision

import org.valkyrienskies.core.api.ships.properties.ShipId

sealed interface CollisionTarget {
    /** A raw VS body ID. Ground-body resolution requires the server's dimension context. */
    data class Body(val id: ShipId) : CollisionTarget
}
