package org.valkyrienskies.vskinetic.collision

import java.util.concurrent.atomic.AtomicBoolean

/** Runtime switches for controlled collision experiments. */
object CollisionExperiment {
    private val authoritativeOnly = AtomicBoolean()
    private val approximateShipPairs = AtomicBoolean()

    fun setAuthoritativeOnly(enabled: Boolean) = authoritativeOnly.set(enabled)

    fun isAuthoritativeOnly(): Boolean = authoritativeOnly.get()

    /** PhysX supplies authoritative ship-pair events; terrain remains approximate for now. */
    fun setApproximateShipPairs(enabled: Boolean) = approximateShipPairs.set(enabled)

    fun isApproximateShipPairsEnabled(): Boolean = approximateShipPairs.get()
}
