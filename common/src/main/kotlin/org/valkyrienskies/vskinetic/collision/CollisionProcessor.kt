package org.valkyrienskies.vskinetic.collision

/** Phase 1 only: drain and record telemetry. No world mutation occurs here. */
object CollisionProcessor {
    fun process(maxRecords: Int = 1024, handler: (Collection<ImpactRecord>) -> Unit = {}) {
        val records = ImpactQueue.drain(maxRecords)
        if (records.isNotEmpty()) handler(coalesce(records))
        CollisionTelemetry.recordProcessed(records)
    }

    /** A VS manifold can contain several points for the same ship pair in one physics tick. */
    private fun coalesce(records: Collection<ImpactRecord>): Collection<ImpactRecord> {
        val authoritativePairs = records.asSequence()
            .filter { it.source == ImpactSource.AUTHORITATIVE }
            .map(::pairKey)
            .toHashSet()
        val grouped = LinkedHashMap<EpisodeKey, ImpactRecord>()
        val result = ArrayList<ImpactRecord>()
        for (record in records) {
            if (record.source != ImpactSource.AUTHORITATIVE) {
                if (pairKey(record) in authoritativePairs) continue
                result += record
                continue
            }
            val pair = pairKey(record)
            val key = EpisodeKey(pair, record.physicsTick)
            val previous = grouped[key]
            if (previous == null || better(record, previous)) grouped[key] = record
        }
        result += grouped.values
        return result
    }

    private fun better(candidate: ImpactRecord, current: ImpactRecord): Boolean =
        candidate.incomingNormalSpeed > current.incomingNormalSpeed ||
            (candidate.incomingNormalSpeed == current.incomingNormalSpeed &&
                candidate.normalAlignment > current.normalAlignment)

    private fun pairKey(record: ImpactRecord): PairKey {
        val first = record.bodyA
        val second = record.bodyB
        return if (targetKey(first) <= targetKey(second)) {
            PairKey(record.dimensionId, first, second)
        } else {
            PairKey(record.dimensionId, second, first)
        }
    }

    private fun targetKey(target: CollisionTarget): Long = when (target) {
        CollisionTarget.Ground -> Long.MIN_VALUE
        is CollisionTarget.Body -> target.id
    }

    private data class EpisodeKey(
        val pair: PairKey,
        val physicsTick: Long
    )

    private data class PairKey(
        val dimensionId: String,
        val first: CollisionTarget,
        val second: CollisionTarget
    )
}
