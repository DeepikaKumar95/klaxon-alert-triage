package com.klaxon.triage.llm;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window limiter sized to the Gemini free tier.
 *
 * A burst of alerts would otherwise exhaust the per-minute quota in seconds and
 * every subsequent alert would fall back to rule-based triage. Blocking briefly
 * here is better than silently degrading the whole batch.
 */
public class RateLimiter {

    private final int maxPerMinute;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimiter(int maxPerMinute) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
    }

    /**
     * @param maxWaitMs give up rather than block a consumer thread indefinitely
     * @return true when a slot was acquired
     */
    public synchronized boolean tryAcquire(long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (true) {
            long now = System.currentTimeMillis();
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= 60_000) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < maxPerMinute) {
                timestamps.addLast(now);
                return true;
            }
            long waitFor = 60_000 - (now - timestamps.peekFirst()) + 25;
            if (now + waitFor > deadline) return false;
            try {
                wait(waitFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public synchronized int usedInLastMinute() {
        long now = System.currentTimeMillis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= 60_000) {
            timestamps.pollFirst();
        }
        return timestamps.size();
    }
}
