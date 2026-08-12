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
import org.valkyrienskies.vskinetic.collision.DebugOverlay
import org.valkyrienskies.vskinetic.collision.ImpactRecord
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.vskinetic.collision.ShipPairCollisionDetector

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
        vsApi.getServerShipWorld()?.let { shipWorld ->
            ShipPairCollisionDetector.scan(shipWorld)
            ShipGroundCollisionDetector.scan(shipWorld, event.server.allLevels)
            CollisionProcessor.process { records ->
                DamagePlanner.plan(records, shipWorld, event.server.allLevels)
            }
            DamageExecutor.process(shipWorld, event.server.allLevels)
            return
        }
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
                .then(
                    net.minecraft.commands.Commands.literal("damage")
                        .then(
                            net.minecraft.commands.Commands.literal("enable")
                                .executes { context ->
                                    DamageExecutor.setEnabled(true)
                                    CollisionTelemetry.setDamageEnabled(true)
                                    context.source.sendSuccess(
                                        { Component.literal("VS: Kinetic destructive damage enabled.") },
                                        true
                                    )
                                    1
                                }
                        )
                        .then(
                            net.minecraft.commands.Commands.literal("disable")
                                .executes { context ->
                                    DamageExecutor.setEnabled(false)
                                    CollisionTelemetry.setDamageEnabled(false)
                                    context.source.sendSuccess(
                                        { Component.literal("VS: Kinetic destructive damage disabled; queued plans cleared.") },
                                        true
                                    )
                                    1
                                }
                        )
                        .then(
                            net.minecraft.commands.Commands.literal("status")
                                .executes { context ->
                                    context.source.sendSuccess(
                                        {
                                            Component.literal(
                                                "VS: Kinetic damage enabled=${DamageExecutor.enabled}, " +
                                                    "pendingPlans=${DamageExecutor.pendingPlans()}, " +
                                                    "pendingOperations=${DamageExecutor.pendingOperations()}"
                                            )
                                        },
                                        true
                                    )
                                    1
                                }
                        )
                )
                .then(
                    net.minecraft.commands.Commands.literal("debug")
                        .then(
                            net.minecraft.commands.Commands.literal("on")
                                .executes { context ->
                                    DebugOverlay.enabled.set(true)
                                    context.source.sendSuccess(
                                        { Component.literal("VS: Kinetic debug overlay enabled.") },
                                        true
                                    )
                                    1
                                }
                        )
                        .then(
                            net.minecraft.commands.Commands.literal("off")
                                .executes { context ->
                                    DebugOverlay.enabled.set(false)
                                    DebugOverlay.clear()
                                    context.source.sendSuccess(
                                        { Component.literal("VS: Kinetic debug overlay disabled.") },
                                        true
                                    )
                                    1
                                }
                        )
                        .then(
                            net.minecraft.commands.Commands.literal("status")
                                .executes { context ->
                                    context.source.sendSuccess(
                                        {
                                            Component.literal(
                                                "VS: Kinetic debug overlay enabled=${DebugOverlay.enabled.get()}"
                                            )
                                        },
                                        true
                                    )
                                    1
                                }
                        )
                )
        )
    }

    private fun formatImpact(impact: ImpactRecord?): String = impact?.let {
        "source=${it.source}, phase=${it.phase}, closing=${"%.2f".format(it.closingSpeed)}, " +
            "pos=(${"%.1f".format(it.contactPositionWorld.x)}, ${"%.1f".format(it.contactPositionWorld.y)}, " +
            "${"%.1f".format(it.contactPositionWorld.z)}), normal=(${"%.2f".format(it.normalWorld.x)}, " +
            "${"%.2f".format(it.normalWorld.y)}, ${"%.2f".format(it.normalWorld.z)}), tick=${it.physicsTick}"
    } ?: "none"

    private fun formatStatus(snapshot: org.valkyrienskies.vskinetic.collision.TelemetrySnapshot): String =
        "VS: Kinetic initialized=${snapshot.initialized}, physicsTicks=${snapshot.physicsTicks}, " +
            "starts=${snapshot.startEvents}, persists=${snapshot.persistEvents}, ends=${snapshot.endEvents}, " +
            "contacts=${snapshot.contacts}, zeroNormals=${snapshot.zeroNormals}, " +
            "rawProbeTicks=${snapshot.rawProbeTicks}, rawEvents=${snapshot.rawEvents}, " +
            "rawProbeFailures=${snapshot.rawProbeFailures}, captured=${snapshot.captured}, " +
            "overlapCandidates=${snapshot.overlapCandidates}, lowSpeedCandidates=${snapshot.lowSpeedCandidates}, " +
            "suppressedCandidates=${snapshot.suppressedCandidates}, " +
            "approximateImpacts=${snapshot.approximateImpacts}, " +
            "terrainCandidates=${snapshot.terrainCandidates}, " +
            "terrainLowSpeedCandidates=${snapshot.terrainLowSpeedCandidates}, " +
            "terrainSuppressedCandidates=${snapshot.terrainSuppressedCandidates}, " +
            "approximateTerrainImpacts=${snapshot.approximateTerrainImpacts}, " +
            "stopEvents=${snapshot.stopEvents}, stopImpacts=${snapshot.stopImpacts}, " +
            "stopNoContact=${snapshot.stopNoContact}, " +
            "plansEvaluated=${snapshot.plansEvaluated}, plansCreated=${snapshot.plansCreated}, " +
            "plansAuthoritative=${snapshot.plansAuthoritative}, plansApproximate=${snapshot.plansApproximate}, " +
            "plansRejectedLowEnergy=${snapshot.plansRejectedLowEnergy}, " +
            "unresolvedContacts=${snapshot.unresolvedContacts}, candidateBlocks=${snapshot.candidateBlocks}, " +
            "plannedBlocks=${snapshot.plannedBlocks}, cappedPlans=${snapshot.cappedPlans}, " +
            "lastImpact=${formatImpact(snapshot.lastImpact)}, lastPlan=${snapshot.lastPlan}, " +
            "damageEnabled=${snapshot.damageEnabled}, damagePlansQueued=${snapshot.damagePlansQueued}, " +
            "damagePlansDropped=${snapshot.damagePlansDropped}, " +
            "damageOperationsAttempted=${snapshot.damageOperationsAttempted}, " +
            "damageBlocksBroken=${snapshot.damageBlocksBroken}, " +
            "damageStaleStateSkips=${snapshot.damageStaleStateSkips}, " +
            "damageBlockEntitySkips=${snapshot.damageBlockEntitySkips}, " +
            "damageUnresolvedTargets=${snapshot.damageUnresolvedTargets}, " +
            "damageFailures=${snapshot.damageFailures}, lastExecution=${snapshot.lastExecution}, " +
            "pendingDamagePlans=${DamageExecutor.pendingPlans()}, " +
            "pendingDamageOperations=${DamageExecutor.pendingOperations()}, " +
            "processed=${snapshot.processed}, dropped=${snapshot.dropped}, " +
            "pending=${org.valkyrienskies.vskinetic.collision.ImpactQueue.pendingCount()}"

}
