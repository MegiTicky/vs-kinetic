package org.valkyrienskies.vskinetic.forge

import org.slf4j.LoggerFactory
import org.valkyrienskies.vskinetic.collision.CollisionTelemetry
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Read-only diagnostic access to the VS Core collision maps. */
object RawCollisionBridgeProbe {
    private val logger = LoggerFactory.getLogger("VS: Kinetic/RawCollisionBridge")
    private var pipelineMethod: Method? = null
    private var pipelineStageField: Field? = null
    private var startMapMethod: Method? = null
    private var persistMapMethod: Method? = null
    private var endMapMethod: Method? = null
    private var failureLogged = false

    fun probe(server: Any) {
        CollisionTelemetry.recordRawProbeTick()
        try {
            val pipeline = (pipelineMethod ?: server.javaClass.getMethod("getVsPipeline").also {
                pipelineMethod = it
            }).invoke(server) ?: return
            val stage = (pipelineStageField ?: findField(pipeline.javaClass, "b").also {
                pipelineStageField = it
            }).get(pipeline) ?: return
            val start = readMap(stage, "d")
            val persist = readMap(stage, "e")
            val end = readMap(stage, "f")
            CollisionTelemetry.recordRawEvents(start + persist + end)
        } catch (failure: Throwable) {
            CollisionTelemetry.recordRawProbeFailure(failure)
            if (!failureLogged) {
                failureLogged = true
                logger.warn("Unable to inspect VS Core collision maps", failure)
            }
        }
    }

    private fun readMap(stage: Any, accessorName: String): Int {
        val accessor = when (accessorName) {
            "d" -> startMapMethod
            "e" -> persistMapMethod
            else -> endMapMethod
        } ?: stage.javaClass.getMethod(accessorName).also {
            when (accessorName) {
                "d" -> startMapMethod = it
                "e" -> persistMapMethod = it
                else -> endMapMethod = it
            }
        }
        return (accessor.invoke(stage) as Map<*, *>).size
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("$name on ${type.name}")
    }
}
