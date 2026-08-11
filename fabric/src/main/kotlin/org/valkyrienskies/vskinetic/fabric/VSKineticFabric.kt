package org.valkyrienskies.vskinetic.fabric

import net.fabricmc.api.ModInitializer

/** Reserved entrypoint for the later Fabric platform implementation. */
class VSKineticFabric : ModInitializer {
    override fun onInitialize() {
        // Collision registration will be enabled after the Fabric VS2 wiring is verified.
    }
}
