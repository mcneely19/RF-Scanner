package com.rfscout.app

/**
 * Keeps a flood of advertisements from swamping the phone. Pure Kotlin, no Android dependencies.
 *
 * - Per-emitter minimum gap: a reading from the same key sooner than the gap is dropped (the tracker
 *   already smooths RSSI and keeps history at 500 ms, so nothing useful is lost).
 * - Safe mode: if more than [ENTER_RATE] readings per second arrive, the gap is raised to
 *   [SAFE_GAP_MS] until the rate falls under [EXIT_RATE].
 * - Counters per protocol feed the "Scan health" screen.
 */
class ReadingGate {
    class Stat {
        var received = 0L
        var dropped = 0L
        var lastMs = 0L
        var rate = 0.0              // readings per second over the last full window (received, before dropping)
        internal var winStart = 0L
        internal var winCount = 0
    }

    private val last = HashMap<String, Long>()
    private val stats = LinkedHashMap<String, Stat>()
    var safeMode = false
        private set
    var safeSinceMs = 0L
        private set
    var safeEntries = 0
        private set
    var evicted = 0L
        private set

    private var winStart = 0L
    private var winCount = 0
    private var totalRate = 0.0

    /** @return true if the reading should be processed. */
    fun admit(proto: String, key: String, nowMs: Long, gapMs: Long): Boolean {
        val st = stats.getOrPut(proto) { Stat() }
        st.received++
        st.lastMs = nowMs
        roll(st, nowMs)
        rollTotal(nowMs)

        val gap = if (safeMode) maxOf(gapMs, SAFE_GAP_MS) else gapMs
        if (gap > 0) {
            val prev = last[key]
            if (prev != null && nowMs - prev in 0 until gap) {
                st.dropped++
                return false
            }
        }
        if (last.size > MAX_KEYS) last.clear()          // bounded memory; at worst one extra reading each
        last[key] = nowMs
        return true
    }

    fun noteEvicted(n: Int) { evicted += n }

    private fun roll(st: Stat, now: Long) {
        if (st.winStart == 0L) st.winStart = now
        st.winCount++
        val el = now - st.winStart
        if (el >= WINDOW_MS) {
            st.rate = st.winCount * 1000.0 / el
            st.winStart = now
            st.winCount = 0
        }
    }

    private fun rollTotal(now: Long) {
        if (winStart == 0L) winStart = now
        winCount++
        val el = now - winStart
        if (el >= WINDOW_MS) {
            totalRate = winCount * 1000.0 / el
            winStart = now
            winCount = 0
            if (!safeMode && totalRate > ENTER_RATE) {
                safeMode = true; safeSinceMs = now; safeEntries++
            } else if (safeMode && totalRate < EXIT_RATE) {
                safeMode = false
            }
        }
    }

    fun totalRate(): Double = totalRate
    fun stat(proto: String): Stat? = stats[proto]
    fun protocols(): Set<String> = stats.keys

    fun clear() {
        last.clear(); stats.clear(); safeMode = false; evicted = 0; winStart = 0; winCount = 0; totalRate = 0.0
    }

    companion object {
        const val WINDOW_MS = 1000L
        const val ENTER_RATE = 300.0
        const val EXIT_RATE = 120.0
        const val SAFE_GAP_MS = 1000L
        const val MAX_KEYS = 6000

        /** Minimum time between processed readings from one emitter. */
        fun gapFor(proto: Protocol): Long = when (proto) {
            Protocol.BLE, Protocol.ADSB, Protocol.RID -> 250L
            else -> 0L
        }
    }
}

/** Chooses which tracks to forget when there are too many. */
object TrackPruner {
    /**
     * @param entries key to last-seen time
     * @param protect keys never removed (selected, watchlist hits, followed tags ...)
     * @return keys to remove, oldest first; empty while under [max].
     */
    fun pick(entries: List<Pair<String, Long>>, max: Int, protect: (String) -> Boolean): List<String> {
        if (entries.size <= max) return emptyList()
        val target = (max * 0.9).toInt()
        val drop = entries.size - target
        return entries.filter { !protect(it.first) }.sortedBy { it.second }.take(drop).map { it.first }
    }
}

/**
 * Cooldown and replay guard for alerts.
 * - A reading older than [maxAgeMs] never alerts (cached or replayed scan results).
 * - After an alert the same key stays quiet for [cooldownMs].
 */
class AlertGate(private val cooldownMs: Long, private val maxAgeMs: Long = 60_000L) {
    private val at = HashMap<String, Long>()

    fun allow(key: String, readingMs: Long, nowMs: Long): Boolean {
        if (nowMs - readingMs > maxAgeMs) return false
        val prev = at[key]
        if (prev != null && nowMs - prev < cooldownMs) return false
        at[key] = nowMs
        return true
    }

    fun remove(key: String) { at.remove(key) }
    fun clear() { at.clear() }
}
