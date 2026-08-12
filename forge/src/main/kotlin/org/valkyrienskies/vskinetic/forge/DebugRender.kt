package org.valkyrienskies.vskinetic.forge

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.joml.Matrix4f
import org.valkyrienskies.vskinetic.VSKineticMod
import org.valkyrienskies.vskinetic.collision.DebugMarker
import org.valkyrienskies.vskinetic.collision.DebugMarkerStyle
import org.valkyrienskies.vskinetic.collision.DebugOverlay
import kotlin.math.floor

/** Client-side debug overlay: world-space markers plus a corner HUD panel. */
@Mod.EventBusSubscriber(modid = VSKineticMod.MOD_ID, value = [Dist.CLIENT])
object DebugRender {
    private const val HUD_LINES = 10

    @SubscribeEvent
    @JvmStatic
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        if (!DebugOverlay.enabled.get()) return
        val markers = DebugOverlay.snapshot()
        if (markers.isEmpty()) return

        val cameraPos = event.camera.position
        val poseStack = event.poseStack

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()

        val tesselator = Tesselator.getInstance()
        val buffer = tesselator.builder
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)
        val matrix = poseStack.last().pose()
        for (marker in markers) {
            drawBox(buffer, matrix, cameraPos, marker)
            marker.direction?.let { dir ->
                line(
                    buffer, matrix, cameraPos,
                    marker.worldPos.x, marker.worldPos.y, marker.worldPos.z,
                    marker.worldPos.x + dir.x * 0.75, marker.worldPos.y + dir.y * 0.75, marker.worldPos.z + dir.z * 0.75,
                    marker.color
                )
            }
        }
        tesselator.end()

        RenderSystem.enableDepthTest()
        RenderSystem.disableBlend()
    }

    @SubscribeEvent
    @JvmStatic
    fun onRenderGui(event: RenderGuiOverlayEvent.Post) {
        if (!DebugOverlay.enabled.get()) return
        val guiGraphics = event.guiGraphics
        val font = Minecraft.getInstance().font
        var y = 4
        guiGraphics.drawString(font, "VS: Kinetic debug", 4, y, 0xFFFFFF)
        y += 10
        val markers = DebugOverlay.snapshot().takeLast(HUD_LINES)
        for (marker in markers) {
            guiGraphics.drawString(font, marker.label, 4, y, marker.color)
            y += 10
        }
    }

    private fun drawBox(buffer: BufferBuilder, matrix: Matrix4f, cameraPos: Vec3, marker: DebugMarker) {
        val x = floor(marker.worldPos.x).toInt()
        val y = floor(marker.worldPos.y).toInt()
        val z = floor(marker.worldPos.z).toInt()
        val minX = x.toDouble()
        val minY = y.toDouble()
        val minZ = z.toDouble()
        val maxX = minX + 1.0
        val maxY = minY + 1.0
        val maxZ = minZ + 1.0
        val color = marker.color
        if (marker.style == DebugMarkerStyle.POINT) {
            drawPoint(buffer, matrix, cameraPos, marker.worldPos.x, marker.worldPos.y, marker.worldPos.z, color)
            return
        }
        // Bottom face
        line(buffer, matrix, cameraPos, minX, minY, minZ, maxX, minY, minZ, color)
        line(buffer, matrix, cameraPos, maxX, minY, minZ, maxX, minY, maxZ, color)
        line(buffer, matrix, cameraPos, maxX, minY, maxZ, minX, minY, maxZ, color)
        line(buffer, matrix, cameraPos, minX, minY, maxZ, minX, minY, minZ, color)
        // Top face
        line(buffer, matrix, cameraPos, minX, maxY, minZ, maxX, maxY, minZ, color)
        line(buffer, matrix, cameraPos, maxX, maxY, minZ, maxX, maxY, maxZ, color)
        line(buffer, matrix, cameraPos, maxX, maxY, maxZ, minX, maxY, maxZ, color)
        line(buffer, matrix, cameraPos, minX, maxY, maxZ, minX, maxY, minZ, color)
        // Vertical edges
        line(buffer, matrix, cameraPos, minX, minY, minZ, minX, maxY, minZ, color)
        line(buffer, matrix, cameraPos, maxX, minY, minZ, maxX, maxY, minZ, color)
        line(buffer, matrix, cameraPos, maxX, minY, maxZ, maxX, maxY, maxZ, color)
        line(buffer, matrix, cameraPos, minX, minY, maxZ, minX, maxY, maxZ, color)
    }

    private fun drawPoint(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        cameraPos: Vec3,
        x: Double,
        y: Double,
        z: Double,
        color: Int
    ) {
        val size = 0.09
        line(buffer, matrix, cameraPos, x - size, y, z, x + size, y, z, color)
        line(buffer, matrix, cameraPos, x, y - size, z, x, y + size, z, color)
        line(buffer, matrix, cameraPos, x, y, z - size, x, y, z + size, color)
    }

    private fun line(
        buffer: BufferBuilder,
        matrix: Matrix4f,
        cameraPos: Vec3,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
        color: Int
    ) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val a = 255
        buffer.vertex(matrix, (x1 - cameraPos.x).toFloat(), (y1 - cameraPos.y).toFloat(), (z1 - cameraPos.z).toFloat())
            .color(r, g, b, a).endVertex()
        buffer.vertex(matrix, (x2 - cameraPos.x).toFloat(), (y2 - cameraPos.y).toFloat(), (z2 - cameraPos.z).toFloat())
            .color(r, g, b, a).endVertex()
    }
}
