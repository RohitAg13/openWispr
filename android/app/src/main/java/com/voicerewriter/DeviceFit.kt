package com.voicerewriter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Picks the on-device model pair that will actually run on *this* phone, before a single byte
 * is downloaded.
 *
 * The default pair — Parakeet (~631MB of weights) plus the cleanup fine-tune (~397MB) — is
 * sized for a current flagship. Onboarding used to download it unconditionally, which is fine
 * on an S25 and a dead end on a 3GB budget phone: the download completes, the first dictation
 * OOMs inside the native runtime, and the user is left at the try-it step with no way forward.
 * That failure lands mid-onboarding, before the app has delivered anything, which is the worst
 * possible moment for it. (A competitor shipping the same architecture has two one-star Play
 * reviews that are precisely this — "Offline voice needs more free memory right now", phone
 * restarted twice, uninstall. See research/11-yaps.md.)
 *
 * So: read total RAM and free storage first, then choose. Every tier is a real, shipped model
 * pair from [ParakeetModelManager]/[WhisperModelManager]/[LlmModelManager] — a smaller tier is
 * a smaller model, never a broken or cloud-backed one, and never a dead end.
 *
 * This is a *starting* choice, not a lock. Settings still offers every engine and model to
 * anyone who wants to override it in either direction.
 */
object DeviceFit {

    /**
     * RAM floors, in reported bytes rather than marketing gigabytes: `totalMem` excludes what
     * the kernel and any carveouts took before Android saw it, so a phone sold as "4GB" reports
     * roughly 3.6-3.7GiB and one sold as "6GB" reports roughly 5.5GiB. The thresholds are set
     * just below each nominal tier for that reason.
     */
    private const val GIB = 1024L * 1024L * 1024L
    private const val FULL_RAM_FLOOR = (5.4 * GIB).toLong()     // ~6GB-class and up
    private const val COMPACT_RAM_FLOOR = (3.4 * GIB).toLong()  // ~4GB-class

    /** Headroom over the weights themselves: unpacking, the .part file, and room to breathe. */
    private const val STORAGE_MARGIN_BYTES = 350L * 1024 * 1024

    enum class Tier {
        /** Parakeet + the cleanup fine-tune. What a flagship gets, and the previous behaviour. */
        FULL,

        /** Whisper base + Gemma 3 270M. Roughly a quarter of the weights, still fully on-device. */
        COMPACT,

        /** Whisper tiny + Gemma 3 270M. The floor: ~316MB total, runs on a 3GB phone. */
        MINIMAL,
    }

    /**
     * @param sttModel id for [OnDeviceStt.resolveModel] — Parakeet or a Whisper size.
     * @param llmModel id for [LlmModelManager].
     * @param ramTier the tier this phone's memory alone allowed. Equal to [tier] normally;
     *   higher than it when free storage was the binding constraint instead. Kept separate so a
     *   caller can tell "your phone is small" from "your phone is full" — they need different
     *   advice, and only one of them is fixable by the user.
     * @param storageShortMb how many MB short of even [Tier.MINIMAL] this device is, or 0 when
     *   the plan fits. Non-zero means the download will fail on space and the user needs to
     *   clear some first — worth saying up front rather than 400MB into a transfer.
     */
    data class Plan(
        val tier: Tier,
        val sttModel: String,
        val llmModel: String,
        val ramTier: Tier = tier,
        val storageShortMb: Long = 0,
    ) {
        val usesParakeet: Boolean get() = OnDeviceStt.isParakeet(sttModel)

        /** Total weight bytes this plan will pull, for the storage check and for copy. */
        val downloadMb: Long
            get() = (sttBytes(sttModel) + llmBytes(llmModel)) / (1024 * 1024)
    }

    /** Total physical RAM as Android reports it, or 0 if the service is unavailable. */
    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        return runCatching {
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }.getOrDefault(0L)
    }

    /**
     * Android's own "this device is memory-constrained" flag. Set by the manufacturer on
     * Go-edition and low-RAM builds, and worth honouring even when `totalMem` looks adequate:
     * it means the platform is already trimming background processes hard, so a 600MB native
     * allocation is far more likely to be the one that gets killed.
     */
    fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { am.isLowRamDevice }.getOrDefault(false)
    }

    /** Free bytes on the partition the models are written to (`filesDir`). */
    fun freeStorageBytes(context: Context): Long =
        runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /**
     * The models this device should start with.
     *
     * Two independent constraints, applied in order: RAM decides the tier the phone can *run*,
     * then storage can only push that further down — never up. A device with 8GB of RAM and
     * 400MB free gets the compact pair, because the full one cannot land.
     */
    fun plan(context: Context): Plan {
        val ram = totalRamBytes(context)
        val lowRam = isLowRamDevice(context)
        val free = freeStorageBytes(context)
        val plan = planFor(ram, lowRam, free)
        Log.i(
            "DeviceFit",
            "ram=${ram / (1024 * 1024)}MB lowRam=$lowRam free=${free / (1024 * 1024)}MB " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} -> ${plan.tier} " +
                "(${plan.sttModel} + ${plan.llmModel}, ${plan.downloadMb}MB)",
        )
        return plan
    }

    /**
     * The decision itself, with the device reads hoisted out — this is where the bugs would
     * live, and it needs no Context to exercise. See DeviceFitTest.
     *
     * @param ramBytes `ActivityManager.MemoryInfo.totalMem`, or 0 when it couldn't be read.
     * @param lowRam Android's own low-RAM device flag.
     * @param freeBytes usable space on the partition the models are written to.
     */
    fun planFor(ramBytes: Long, lowRam: Boolean, freeBytes: Long): Plan {
        // ramBytes == 0 means ActivityManager didn't answer. Assume capable rather than
        // punishing a phone for an unreadable service — the storage check still applies.
        val byRam = when {
            lowRam -> Tier.MINIMAL
            ramBytes == 0L -> Tier.FULL
            ramBytes >= FULL_RAM_FLOOR -> Tier.FULL
            ramBytes >= COMPACT_RAM_FLOOR -> Tier.COMPACT
            else -> Tier.MINIMAL
        }

        // Storage can only push the tier down, never up: a 12GB phone with 300MB free still
        // cannot land a 1GB pair, and finding that out mid-download is the failure this whole
        // object exists to avoid.
        var tier = byRam
        while (tier != Tier.MINIMAL && bytesFor(tier) + STORAGE_MARGIN_BYTES > freeBytes) {
            tier = if (tier == Tier.FULL) Tier.COMPACT else Tier.MINIMAL
        }

        val shortBy = (bytesFor(Tier.MINIMAL) + STORAGE_MARGIN_BYTES - freeBytes).coerceAtLeast(0)
        return Plan(
            tier = tier,
            sttModel = sttFor(tier),
            llmModel = llmFor(tier),
            ramTier = byRam,
            storageShortMb = shortBy / (1024 * 1024),
        )
    }

    /**
     * One line for the user, or null when there is nothing worth saying. Deliberately states
     * the reason and the consequence in plain terms — a smaller model is a real tradeoff and
     * pretending otherwise is how you get a one-star review about accuracy instead.
     */
    fun explain(plan: Plan): String? = when {
        plan.storageShortMb > 0 ->
            "This phone is about ${plan.storageShortMb}MB short of free space for the speech model. " +
                "Free some up and reopen OpenWispr, or the download will stop partway."
        plan.tier != plan.ramTier ->
            "OpenWispr picked a smaller speech model (${plan.downloadMb}MB) because this phone is " +
                "low on free space, not because it can't run the larger one. Free some space and " +
                "you can switch to it in Settings."
        plan.tier == Tier.COMPACT ->
            "Your phone has less memory than the largest speech model needs, so OpenWispr will " +
                "use a lighter one (${plan.downloadMb}MB instead of about 1GB). Still fully " +
                "on-device — a little less accurate on long or noisy speech. You can switch to " +
                "the large model in Settings."
        plan.tier == Tier.MINIMAL ->
            "OpenWispr picked its smallest speech model (${plan.downloadMb}MB) so it runs " +
                "comfortably on this phone. Still fully on-device — best with short, clear " +
                "dictation. Larger models are in Settings if you want to try one."
        else -> null
    }

    /** Short label for Settings, where the user is choosing a model themselves. */
    fun recommendationLabel(context: Context): String {
        val plan = plan(context)
        val ramGb = totalRamBytes(context).toDouble() / GIB
        val ram = if (ramGb > 0) String.format("%.1fGB RAM", ramGb) else "unknown RAM"
        val stt = if (plan.usesParakeet) ParakeetModelManager.LABEL
        else WhisperModelManager.model(plan.sttModel).label
        return "This device: $ram — recommended: $stt"
    }

    private fun sttFor(tier: Tier) = when (tier) {
        Tier.FULL -> ParakeetModelManager.MODEL_ID
        Tier.COMPACT -> "base"
        Tier.MINIMAL -> "tiny"
    }

    private fun llmFor(tier: Tier) = when (tier) {
        Tier.FULL -> LlmModelManager.DEFAULT_MODEL
        else -> "gemma3-270m"
    }

    private fun bytesFor(tier: Tier) = sttBytes(sttFor(tier)) + llmBytes(llmFor(tier))

    // Approximate download sizes, matching the sizeLabel strings the model registries already
    // show the user. Only ever used for a "will this fit" comparison against free space, so
    // being a few MB out is harmless — being wrong by a tier is not.
    private fun sttBytes(id: String): Long = when {
        OnDeviceStt.isParakeet(id) -> 631L * 1024 * 1024
        id == "small" -> 488L * 1024 * 1024
        id == "base" -> 142L * 1024 * 1024
        else -> 75L * 1024 * 1024
    }

    private fun llmBytes(id: String): Long = when (id) {
        "gemma3-270m" -> 241L * 1024 * 1024
        "qwen3-0.6b" -> 639L * 1024 * 1024
        else -> 397L * 1024 * 1024
    }
}
