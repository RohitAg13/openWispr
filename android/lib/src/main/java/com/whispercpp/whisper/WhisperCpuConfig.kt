package com.whispercpp.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

object WhisperCpuConfig {
    // ggml synchronises its threads once per layer, so the slowest thread sets the pace:
    // spilling onto genuine "little" cores makes every layer wait on a straggler and is
    // slower overall. That is why this used to be capped at 4. But a flat cap leaves half
    // of a modern SoC idle -- parts like the Snapdragon 8 Elite have no little cores at
    // all, and there 6 threads beat 4 by ~19% on a whisper-base model (1370ms vs 1691ms
    // on a 4.82s clip). 8 threads measured *worse* than 4 (2022ms), presumably from
    // contending with the OS and the foreground app, so we never take the whole SoC.
    //
    // The rule: count the cores that are not little, and if every core is a big one, hand
    // two of them back to the rest of the system. On a big.LITTLE part the little cores
    // already absorb that work, so there we use all of the big ones -- which for a 4+4
    // phone is the same 4 threads as before.
    //
    // The old cap is also kept as a floor, so this can only ever ask for more threads
    // than we used to, never fewer. That matters because nobody has measured the parts
    // this code has to guess about: without the floor, an all-big 4-core SoC would
    // reserve its way down to 2 threads, and a 2-big-plus-6-little one down to 2 as
    // well, both of which were 4 before. Anything we cannot read out of /proc/cpuinfo
    // falls back to that same cap.
    private const val LOG_TAG = "WhisperCpuConfig"
    private const val MAX_THREADS = 6
    private const val RESERVED_CORES = 2

    val preferredThreadCount: Int
        get() = try {
            val cores = Runtime.getRuntime().availableProcessors()
            val bigCores = CpuInfo.getBigCoreCount()
            val threads = if (bigCores < cores) bigCores else bigCores - RESERVED_CORES
            threads.coerceIn(conservativeThreadCount(cores), MAX_THREADS)
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "Couldn't size the thread pool from /proc/cpuinfo", e)
            conservativeThreadCount(Runtime.getRuntime().availableProcessors())
        }

    /** What we shipped before any of this: assume a phone SoC of ~1 prime + 3 big cores. */
    private fun conservativeThreadCount(cores: Int) = cores.coerceIn(2, 4)
}

private class CpuInfo(private val lines: List<String>) {
    /**
     * The number of cores that are not known efficiency ("little") cores.
     *
     * Frequency alone cannot tell the two kinds apart -- a Cortex-A55 clocked at 75% of
     * the prime core does nowhere near 75% of its work -- so we go by the ARM part number
     * and exclude the little designs we know by name. Unknown parts count as big: custom
     * cores (Qualcomm's Oryon, for one) report implementer-specific part numbers, and the
     * thread count is clamped afterwards anyway.
     *
     * Throws if /proc/cpuinfo does not say anything usable, so the caller can fall back.
     */
    private fun getBigCoreCount(): Int {
        val parts = getCpuValues(property = "CPU part") { it.substringAfter("0x").toInt(radix = 16) }
            .also { Log.d(LOG_TAG, "Binned cpu parts (part, count): ${it.binnedValues()}") }
        require(parts.isNotEmpty()) { "No CPU part lines in /proc/cpuinfo" }
        val bigCores = parts.count { it !in LITTLE_CPU_PARTS }
        require(bigCores > 0) { "Every core looks like a little core" }
        return bigCores
    }

    private fun List<Int>.binnedValues() = groupingBy { it }.eachCount()

    private fun getCpuValues(property: String, mapper: (String) -> Int) = lines
        .asSequence()
        .filter { it.startsWith(property) }
        .map { mapper(it.substringAfter(':').trim()) }
        .sorted()
        .toList()

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        /** ARM's efficiency cores, by part number: Cortex-A53, A55, A510, A520. */
        private val LITTLE_CPU_PARTS = setOf(0xd03, 0xd05, 0xd46, 0xd80)

        fun getBigCoreCount(): Int = readCpuInfo().getBigCoreCount()

        private fun readCpuInfo() = CpuInfo(
            BufferedReader(FileReader("/proc/cpuinfo"))
                .useLines { it.toList() }
        )
    }
}
