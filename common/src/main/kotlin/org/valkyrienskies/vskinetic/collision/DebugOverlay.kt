package org.valkyrienskies.vskinetic.collision

import org.joml.Vector3d
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Marker colors shared by the debug overlay renderer. */
object DebugColors {
    const val VS_CONTACT = 0xFFFFFF
    const val TERRAIN_CONTACT = 0x00FF00
    const val LOW_SPEED = 0x808080
    const val PAIR_CONTACT = 0xFF00FF
    const val PLAN = 0x00FFFF
    const val PLAN_REJECT = 0xFFFF00
    const val UNRESOLVED = 0xFFFF00
    const val BROKEN = 0x00FF00
    const val STALE = 0xFFA500
    const val BLOCK_ENTITY = 0xFFA500
    const val FAILED = 0xFF0000
    const val UNRESOLVED_TARGET = 0xFFFF00
}

/** One in-world debug marker; position and optional direction arrow in world space. */
data class DebugMarker(
    val worldPos: Vector3d,
    val label: String,
    val color: Int,
    val direction: Vector3d?,
    val style: DebugMarkerStyle,
    val createdAtMs: Long
)

enum class DebugMarkerStyle {
    BLOCK,
    POINT
}

/**
 * Thread-safe, bounded store of debug markers written by the physics and server threads
 * and read by the client render thread. Loader-agnostic: holds no Minecraft references.
 */
object DebugOverlay {
    private const val MAX_MARKERS = 2048
    private const val DEFAULT_MAX_AGE_MS = 3000L
    private val markers = CopyOnWriteArrayList<DebugMarker>()
    val enabled = AtomicBoolean()

    fun record(
        worldPos: Vector3d,
        label: String,
        color: Int,
        direction: Vector3d? = null,
        style: DebugMarkerStyle = DebugMarkerStyle.BLOCK
    ) {
        if (!enabled.get()) return
        markers += DebugMarker(
            Vector3d(worldPos),
            label,
            color,
            direction?.let(::Vector3d),
            style,
            System.currentTimeMillis()
        )
        while (markers.size > MAX_MARKERS) markers.removeAt(0)
    }

    fun snapshot(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): List<DebugMarker> {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        markers.removeIf { it.createdAtMs < cutoff }
        return markers.toList()
    }

    fun clear() = markers.clear()
}
