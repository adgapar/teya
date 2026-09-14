package com.teya.agent.messaging

/**
 * A per-number cap on how many inbound messages Teya will actually answer in a window.
 *
 * Cheap, and it blunts two different problems with the same knob: someone spoofing a household
 * number to drive the agent, and a stuck sender (or a message loop) burning API calls and SMS
 * segments while nobody is watching the wall device.
 *
 * Deliberately in memory only. Persisting it would survive a restart, but the thing it protects
 * against is a burst — and a burst that manages to also kill a foreground service between messages
 * has bigger problems than its rate limit being forgotten.
 */
class InboundRateLimiter(
    private val maxPerWindow: Int = 20,
    private val windowMs: Long = 60 * 60 * 1000L,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** True if this message is within the sender's budget (and counts it); false to drop it. */
    @Synchronized
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val window = hits.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > windowMs) window.removeFirst()
        if (window.size >= maxPerWindow) return false
        window.addLast(now)
        return true
    }
}
