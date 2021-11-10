/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package kotlin.native.internal

/**
 *  ## Cycle garbage collector interface.
 *
 * Konan relies upon reference counting for object management, however it could
 * not collect cyclical garbage, so we perform periodic garbage collection.
 * This may slow down application, so this interface provides control over how
 * garbage collector activates and runs.
 * Garbage collector can be in one of the following states:
 *  - running
 *  - suspended (so cycle candidates are collected, but GC is not performed until resume)
 *  - stopped (all cyclical garbage is hopelessly lost)
 * Immediately after startup GC is in running state.
 * Depending on application needs it may select to suspend GC for certain phases of
 * its lifetime, and resume it later on, or just completely turn it off, if GC pauses
 * are less desirable than cyclical garbage leaks.
 */
object GC {
    /**
     * To force garbage collection immediately, unless collector is stopped
     * with [stop] operation. Even if GC is suspended, [collect] still triggers collection.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_collect")
    external fun collect()

    /**
     * Request global cyclic collector, operation is async and just triggers the collection.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_collectCyclic")
    external fun collectCyclic()

    /**
     * Suspend garbage collection. Release candidates are still collected, but
     * GC algorithm is not executed.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_suspend")
    external fun suspend()

    /**
     * Resume garbage collection. Can potentially lead to GC immediately.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_resume")
    external fun resume()

    /**
     * Stop garbage collection. Cyclical garbage is no longer collected.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_stop")
    external fun stop()

    /**
     * Start garbage collection. Cyclical garbage produced while GC was stopped
     * cannot be reclaimed, but all new garbage is collected.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_start")
    external fun start()

    /**
     * GC threshold, controlling how frequenly GC is activated, and how much time GC
     * takes. Bigger values lead to longer GC pauses, but less GCs.
     * New MM: usually unused. For the on-safepoints GC scheduler counts
     *         how many safepoints must the code pass before informing the GC scheduler.
     */
    var threshold: Int
        get() = getThreshold()
        set(value) = setThreshold(value)

    /**
     * GC allocation threshold, controlling how frequenly GC collect cycles, and how much time
     * this process takes. Bigger values lead to longer GC pauses, but less GCs.
     * New MM: unused.
     */
    var collectCyclesThreshold: Long
        get() = getCollectCyclesThreshold()
        set(value) = setCollectCyclesThreshold(value)

    /**
     * GC allocation threshold, controlling how many bytes allocated since last
     * collection will trigger new GC.
     * New MM: how many bytes a thread can allocate before informing the GC scheduler.
     */
    var thresholdAllocations: Long
        get() = getThresholdAllocations()
        set(value) = setThresholdAllocations(value)

    /**
     * If GC shall auto-tune thresholds, depending on how much time is spent in collection.
     * New MM: if true update targetHeapBytes after each collection.
     */
    var autotune: Boolean
        get() = getTuneThreshold()
        set(value) = setTuneThreshold(value)


    /**
     * If cyclic collector for atomic references to be deployed.
     * New MM: unused.
     */
    var cyclicCollectorEnabled: Boolean
        get() = getCyclicCollectorEnabled()
        set(value) = setCyclicCollectorEnabled(value)

    /**
     * New MM only. Unused with on-safepoints GC scheduler.
     * When Kotlin code is not allocating enough to trigger GC, the GC scheduler uses timer to drive collection.
     * This timer induced collection will not happen sooner than regularGCIntervalMicroseconds after the
     * previous collection (any kind), and not later than 2 * regularGCIntervalMicroseconds.
     */
     var regularGCIntervalMicroseconds: Long
        get() = getRegularGCIntervalMicroseconds()
        set(value) = setRegularGCIntervalMicroseconds(value)

    /**
     * New MM only.
     * Total amount of heap available for Kotlin objects. When Kotlin objects overflow this heap,
     * the garbage collection is requested. Automatically adjusts when autotune is true:
     * after each collection the targetHeapBytes is set to heapBytes / targetHeapUtilization and
     * capped between minHeapBytes and maxHeapBytes, where heapBytes is heap usage after the garbage
     * is collected.
     * Note, that if after a collection heapBytes > targetHeapBytes (which may happen if autotune is false,
     * or maxHeapBytes is set too low), the next collection will be triggered almost immediately.
     */
    var targetHeapBytes: Long
        get() = getTargetHeapBytes()
        set(value) = setTargetHeapBytes(value)

    /**
     * New MM only.
     * How much of the Kotlin heap should be populated.
     * Only used if autotune is true. See targetHeapBytes for more details.
     */
     var targetHeapUtilization: Double
        get() = getTargetHeapUtilization()
        set(value) = setTargetHeapUtilization(value)

    /**
     * New MM only.
     * The minimum value for targetHeapBytes
     * Only used if autotune is true. See targetHeapBytes for more details.
     */
     var minHeapBytes: Long
        get() = getMinHeapBytes()
        set(value) = setMinHeapBytes(value)

    /**
     * New MM only.
     * The maximum value for targetHeapBytes. Use -1 for infinity.
     * Only used if autotune is true. See targetHeapBytes for more details.
     */
     var maxHeapBytes: Long
        get() = getMaxHeapBytes()
        set(value) = setMaxHeapBytes(value)

    /**
     * Detect cyclic references going via atomic references and return list of cycle-inducing objects
     * or `null` if the leak detector is not available. Use [Platform.isMemoryLeakCheckerActive] to check
     * leak detector availability.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_detectCycles")
    external fun detectCycles(): Array<Any>?

    /**
     * Find a reference cycle including from the given object, `null` if no cycles detected.
     */
    @GCUnsafeCall("Kotlin_native_internal_GC_findCycle")
    external fun findCycle(root: Any): Array<Any>?

    @GCUnsafeCall("Kotlin_native_internal_GC_getThreshold")
    private external fun getThreshold(): Int

    @GCUnsafeCall("Kotlin_native_internal_GC_setThreshold")
    private external fun setThreshold(value: Int)

    @GCUnsafeCall("Kotlin_native_internal_GC_getCollectCyclesThreshold")
    private external fun getCollectCyclesThreshold(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setCollectCyclesThreshold")
    private external fun setCollectCyclesThreshold(value: Long)

    @GCUnsafeCall("Kotlin_native_internal_GC_getThresholdAllocations")
    private external fun getThresholdAllocations(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setThresholdAllocations")
    private external fun setThresholdAllocations(value: Long)

    @GCUnsafeCall("Kotlin_native_internal_GC_getTuneThreshold")
    private external fun getTuneThreshold(): Boolean

    @GCUnsafeCall("Kotlin_native_internal_GC_setTuneThreshold")
    private external fun setTuneThreshold(value: Boolean)

    @GCUnsafeCall("Kotlin_native_internal_GC_getCyclicCollector")
    private external fun getCyclicCollectorEnabled(): Boolean

    @GCUnsafeCall("Kotlin_native_internal_GC_setCyclicCollector")
    private external fun setCyclicCollectorEnabled(value: Boolean)

    @GCUnsafeCall("Kotlin_native_internal_GC_getRegularGCIntervalMicroseconds")
    private external fun getRegularGCIntervalMicroseconds(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setRegularGCIntervalMicroseconds")
    private external fun setRegularGCIntervalMicroseconds(value: Long)

    @GCUnsafeCall("Kotlin_native_internal_GC_getTargetHeapBytes")
    private external fun getTargetHeapBytes(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setTargetHeapBytes")
    private external fun setTargetHeapBytes(value: Long)

    @GCUnsafeCall("Kotlin_native_internal_GC_getTargetHeapUtilization")
    private external fun getTargetHeapUtilization(): Double

    @GCUnsafeCall("Kotlin_native_internal_GC_setTargetHeapUtilization")
    private external fun setTargetHeapUtilization(value: Double)

    @GCUnsafeCall("Kotlin_native_internal_GC_getMinHeapBytes")
    private external fun getMinHeapBytes(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setMinHeapBytes")
    private external fun setMinHeapBytes(value: Long)

    @GCUnsafeCall("Kotlin_native_internal_GC_getMaxHeapBytes")
    private external fun getMaxHeapBytes(): Long

    @GCUnsafeCall("Kotlin_native_internal_GC_setMaxHeapBytes")
    private external fun setMaxHeapBytes(value: Long)
}
