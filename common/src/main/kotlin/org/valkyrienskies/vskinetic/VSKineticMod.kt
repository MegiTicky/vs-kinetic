package org.valkyrienskies.vskinetic

import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.vskinetic.collision.CollisionCapture

/** Common initialization shared by all loaders. */
object VSKineticMod {
    const val MOD_ID = "vskinetic"

    @OptIn(VsBeta::class)
    fun init() {
        vsApi.collisionStartEvent.on(CollisionCapture::capture)
        vsApi.physTickEvent.on { CollisionCapture.nextPhysicsTick() }
    }
}
