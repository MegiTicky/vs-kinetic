package org.valkyrienskies.vskinetic

import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.vskinetic.collision.CollisionCapture
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry

/** Common initialization shared by all loaders. */
object VSKineticMod {
    const val MOD_ID = "vskinetic"

    @OptIn(VsBeta::class)
    fun init() {
        vsApi.collisionStartEvent.on(CollisionCapture::onStart)
        vsApi.collisionPersistEvent.on(CollisionCapture::onPersist)
        vsApi.collisionEndEvent.on(CollisionCapture::onEnd)
        vsApi.physTickEvent.on { CollisionCapture.nextPhysicsTick() }
        CollisionTelemetry.markInitialized()
    }
}
