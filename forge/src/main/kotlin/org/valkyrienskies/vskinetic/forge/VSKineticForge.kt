package org.valkyrienskies.vskinetic.forge

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import org.valkyrienskies.vskinetic.VSKineticMod
import org.valkyrienskies.vskinetic.collision.CollisionProcessor
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry

@Mod(VSKineticMod.MOD_ID)
class VSKineticForge {
    init {
        FMLJavaModLoadingContext.get().modEventBus.addListener(::onCommonSetup)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)
    }

    private fun onCommonSetup(@Suppress("UNUSED_PARAMETER") event: FMLCommonSetupEvent) {
        VSKineticMod.init()
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        CollisionProcessor.process()
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerCommands(event.dispatcher)
    }

    private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal("vskinetic")
                .requires { it.hasPermission(2) }
                .then(
                    net.minecraft.commands.Commands.literal("status")
                        .executes { context ->
                            val snapshot = CollisionTelemetry.snapshot()
                            context.source.sendSuccess(
                                { Component.literal(formatStatus(snapshot)) },
                                true
                            )
                            1
                        }
                )
        )
    }

    private fun formatStatus(snapshot: org.valkyrienskies.vskinetic.collision.TelemetrySnapshot): String =
        "VS: Kinetic telemetry: captured=${snapshot.captured}, processed=${snapshot.processed}, " +
            "dropped=${snapshot.dropped}, pending=${org.valkyrienskies.vskinetic.collision.ImpactQueue.pendingCount()}"

}
