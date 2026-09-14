package com.teya.agent.messaging

/** In-memory sliding-window cap on inbound messages answered per sender. */
class InboundRateLimiter(
    private val maxPerWindow: Int = 20,
    private val windowMs: Long = 60 * 60 * 1000L,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** True if within budget (and counts it); false to drop the message. */
    @Synchronized
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val window = hits.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > windowMs) window.removeFirst()
        if (window.size >= maxPerWindow) return false
        window.addLast(now)
        return true
    }
}
