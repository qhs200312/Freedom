package com.v2ray.ang.util

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Return as soon as one bounded probe succeeds; a slow endpoint cannot block a healthy one. */
internal fun firstReachable(
    urls: List<String>,
    timeoutMs: Long = 5_000L,
    probe: (String) -> Boolean,
): Boolean {
    val candidates = urls.filter(String::isNotBlank).distinct().take(2)
    if (candidates.isEmpty()) return false
    val executor = Executors.newFixedThreadPool(candidates.size) { task ->
        Thread(task, "startup-reachability").apply { isDaemon = true }
    }
    val completion = ExecutorCompletionService<Boolean>(executor)
    val futures = candidates.map { url -> completion.submit { runCatching { probe(url) }.getOrDefault(false) } }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    return try {
        repeat(candidates.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            val finished = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return false
            if (finished.get()) return true
        }
        false
    } finally {
        futures.forEach { it.cancel(true) }
        executor.shutdownNow()
    }
}
