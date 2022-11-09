/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.sync

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlin.contracts.*
import kotlin.jvm.*
import kotlin.native.concurrent.SharedImmutable

/**
 * A read-write mutex for coroutines that logically maintains a number of available reading permits, and a single writer lock.
 * ReadWriteMutex is fair and maintains a FIFO order of acquirers, except for mutual exclusion when writing.
 */
public interface ReadWriteMutex : Semaphore, Mutex {
    /**
     * Returns the current number of read permits available in this read write mutex.
     */
    public val availablePermitsForReading: Int

    /**
     * Returns `true` when this read write mutex is locked for writing.
     */
    public val isLockedForWriting: Boolean

    /**
     * Returns `true` when this read write mutex has a pending write lock.
     */
    public val isWaitingToWrite: Boolean

    /**
     * Returns the current number of read permits available in this read write mutex.
     */
    override val availablePermits: Int
        get() = availablePermitsForReading

    /**
     * Returns `true` when this read write mutex is locked for writing.
     */
    override val isLocked: Boolean
        get() = isLockedForWriting

    /**
     * Acquires a reading permit from this read write mutex, suspending until one is available **or** if a write operation is underway.
     * All suspending acquirers are processed in first-in-first-out (FIFO) order.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
     * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
     * This function releases the semaphore if it was already acquired by this function before the [CancellationException]
     * was thrown.
     *
     * Note, that this function does not check for cancellation when it does not suspend.
     * Use [CoroutineScope.isActive] or [CoroutineScope.ensureActive] to periodically
     * check for cancellation in tight loops if needed.
     *
     * Use [tryAcquireForReading] to try acquire a reading permit of this read write mutex without suspension.
     */
    public suspend fun acquireForReading()

    /**
     * Locks this read write mutex, suspending caller while the read write mutex is locked.
     *
     * Note that if any read operations are currently underway, this caller will suspend.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
     * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
     * This function releases the lock if it was already acquired by this function before the [CancellationException]
     * was thrown.
     *
     * Note that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     *
     * Use [tryLockForWriting] to try acquiring a lock without waiting.
     *
     * This function is fair; suspended callers are resumed in first-in-first-out order.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this read write mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    public suspend fun lockForWriting(owner: Any? = null)

    /**
     * Acquires a reading permit from this read write mutex, suspending until one is available **or** if a write operation is underway.
     * All suspending acquirers are processed in first-in-first-out (FIFO) order.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
     * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
     * This function releases the semaphore if it was already acquired by this function before the [CancellationException]
     * was thrown.
     *
     * Note, that this function does not check for cancellation when it does not suspend.
     * Use [CoroutineScope.isActive] or [CoroutineScope.ensureActive] to periodically
     * check for cancellation in tight loops if needed.
     *
     * Use [tryAcquireForReading] to try acquire a reading permit of this read write mutex without suspension.
     */
    override suspend fun acquire(): Unit =
        acquireForReading()

    /**
     * Locks this read write mutex, suspending caller while the read write mutex is locked.
     *
     * Note that if any read operations are currently underway, this caller will suspend.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
     * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
     * This function releases the lock if it was already acquired by this function before the [CancellationException]
     * was thrown.
     *
     * Note that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     *
     * Use [tryLockForWriting] to try acquiring a lock without waiting.
     *
     * This function is fair; suspended callers are resumed in first-in-first-out order.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this read write mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    override suspend fun lock(owner: Any?): Unit =
        lockForWriting(owner)

    /**
     * Tries to acquire a reading permit from this read write mutex without suspension.
     *
     * @return `true` if a reading permit was acquired, `false` otherwise.
     */
    public fun tryAcquireForReading(): Boolean

    /**
     * Tries to lock this read write mutex, returning `false` if this read write mutex is already locked **or** if a reading operation is underway.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    public fun tryLockForWriting(owner: Any? = null): Boolean

    /**
     * Tries to acquire a reading permit from this read write mutex without suspension.
     *
     * @return `true` if a reading permit was acquired, `false` otherwise.
     */
    override fun tryAcquire(): Boolean =
        tryAcquireForReading()

    /**
     * Tries to lock this read write mutex, returning `false` if this read write mutex is already locked **or** if a reading operation is underway.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    override fun tryLock(owner: Any?): Boolean =
        tryLockForWriting(owner)

    /**
     * Releases a reading permit, returning it into this read write mutex. Resumes the first
     * suspending acquirer if there is one at the point of invocation.
     * Throws [IllegalStateException] if the number of [releaseFromReading] invocations is greater than the number of preceding [acquireForReading]
     * **or** if a writing operation is currently underway.
     */
    public fun releaseFromReading()

    /**
     * Unlocks this read write mutex. Throws [IllegalStateException] if invoked on a read write mutex that is not locked or
     * was locked with a different owner token (by identity) **or** if a reading operation is currently underway.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        was locked with the different token (by identity), this function throws [IllegalStateException].
     */
    public fun unlockFromWriting(owner: Any? = null)

    /**
     * Releases a reading permit, returning it into this read write mutex. Resumes the first
     * suspending acquirer if there is one at the point of invocation.
     * Throws [IllegalStateException] if the number of [releaseFromReading] invocations is greater than the number of preceding [acquireForReading]
     * **or** if a writing operation is currently underway.
     */
    override fun release(): Unit =
        releaseFromReading()

    /**
     * Unlocks this read write mutex. Throws [IllegalStateException] if invoked on a read write mutex that is not locked or
     * was locked with a different owner token (by identity) **or** if a reading operation is currently underway.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        was locked with the different token (by identity), this function throws [IllegalStateException].
     */
    override fun unlock(owner: Any?): Unit =
        unlockFromWriting(owner)
}

/**
 * Creates new [ReadWriteMutex] instance.
 * @param permits the number of permits available in this ReadWriteMutex.
 * @param acquiredPermits the number of already acquired permits,
 *        should be between `0` and `permits` (inclusively).
 * @param locked initial lock state of the read write mutex.
 */
public fun ReadWriteMutex(permits: Int, acquiredPermits: Int = 0, locked: Boolean = false): ReadWriteMutex =
    ReadWriteMutexImpl(permits, acquiredPermits, locked)

/**
 * Executes the given [action], acquiring a read permit from this ReadWriteMutex at the beginning
 * and releasing it after the [action] is completed.
 *
 * @return the return value of the [action].
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <T> ReadWriteMutex.withPermitForReading(action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }

    acquireForReading()
    try {
        return action()
    } finally {
        releaseFromReading()
    }
}

/**
 * Executes the given [action] under this read write mutex's write lock.
 *
 * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
 *        is already locked with the same token (same identity), this function throws [IllegalStateException].
 *
 * @return the return value of the action.
 */
@OptIn(ExperimentalContracts::class)
public suspend inline fun <T> ReadWriteMutex.withLockForWriting(owner: Any? = null, action: () -> T): T {
    contract {
        callsInPlace(action, InvocationKind.EXACTLY_ONCE)
    }

    lockForWriting(owner)
    try {
        return action()
    } finally {
        unlockFromWriting(owner)
    }
}

@SharedImmutable
private val LOCKED = Symbol("LOCKED")

@SharedImmutable
private val WRITING_EMPTY = Writing(LOCKED)

private class Writing(
    @JvmField val owner: Any
) {
    override fun toString(): String = "Writing[$owner]"
}

private class ReadWriteMutexImpl(private val permits: Int, acquiredPermits: Int, locked: Boolean) : ReadWriteMutex {
    /*
       Despite being called a 'ReadWriteMutex', the backing code for this class is very largely based on `SemaphoreImpl`,
       using an infinite array based on a list of segments. Using the same enqueue-dequeue pair, but with slightly more
       complicated dequeue logic.

       The main changes come to how our internal state logic works; instead of using a single integer for state, we use an
       atomic state reference that contains either our lock, or the number of permits currently available. Then, we have a
       second atomic int that is our number of *queued* locks (allowing us to suspend read calls while waiting for a lock).

     */

    private val head: AtomicRef<ReadWriteLockSegment>
    private val deqIdx = atomic(0L)
    private val tail: AtomicRef<ReadWriteLockSegment>
    private val enqIdx = atomic(0L)

    private val _state = atomic<Any>(if (locked) WRITING_EMPTY else permits - acquiredPermits)
    private val _queuedLocks = atomic(if (locked) 1 else 0)

    init {
        require(permits > 0) { "ReadWriteLock should have at least 1 permit, but had $permits" }
        require(acquiredPermits in 0..permits) { "The number of acquired permits should be in 0..$permits" }
        require(!locked || acquiredPermits == 0) { "ReadWriteLock cannot have permits acquired and also be locked" }
        val s = ReadWriteLockSegment(0, null, 2)
        head = atomic(s)
        tail = atomic(s)
    }

    override val availablePermitsForReading: Int
        get() {
            if (_queuedLocks.value > 0) return 0

            _state.loop { state ->
                when (state) {
                    is Int -> return state
                    is Writing -> return 0
                    else -> error("Illegal state $state")
                }
            }
        }

    override val isLockedForWriting: Boolean
        get() {
            _state.loop { state ->
                when (state) {
                    is Int -> return false
                    is Writing -> return true
                    else -> error("Illegal state $state")
                }
            }
        }

    override val isWaitingToWrite: Boolean
        get() = _queuedLocks.value > 0

    private val onCancellationRelease = { _: Throwable -> releaseFromReading() }

    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated(
        "Mutex.onLock deprecated without replacement. For additional details please refer to #2794",
        level = DeprecationLevel.WARNING
    )
    override val onLock: SelectClause2<Any?, Mutex>
        get() = throw UnsupportedOperationException("Mutex.onLock deprecated without replacement. For additional details please refer to #2794")

    override fun tryAcquireForReading(): Boolean {
        if (_queuedLocks.value > 0) return false

        _state.loop { state ->
            when (state) {
                is Int -> {
                    if (state <= 0) return false
                    if (_state.compareAndSet(state, state - 1)) return true
                }

                is Writing -> return false
                else -> error("Illegal state $state")
            }
        }
    }

    override fun tryLockForWriting(owner: Any?): Boolean {
        if (_queuedLocks.value > 0) return false

        _state.loop { state ->
            when (state) {
                is Int -> {
                    if (state < permits) return false
                    val update = if (owner == null) WRITING_EMPTY else Writing(owner)
                    if (_queuedLocks.compareAndSet(0, 1) && _state.compareAndSet(state, update)) return true
                }

                is Writing -> return false
                else -> error("Illegal state $state")
            }
        }
    }

    override suspend fun acquireForReading() {
        if (tryAcquireForReading()) return

        // While it looks better when the following function is inlined,
        // it is important to make `suspend` function invocations in a way
        // so that the tail-call optimization can be applied.
        acquireSlowPath()
    }

    override suspend fun lockForWriting(owner: Any?) {
        _queuedLocks.incrementAndGet()

        _state.loop { state ->
            when (state) {
                is Int -> {
                    if (state < permits) return lockSlowPath(owner)
                    val update = if (owner == null) WRITING_EMPTY else Writing(owner)
                    if (_state.compareAndSet(state, update)) {
                        return
                    }
                }

                is Writing -> return lockSlowPath(owner)
                else -> error("Illegal state $state")
            }
        }
    }

    override fun holdsLock(owner: Any): Boolean =
        _state.value.let { state ->
            when (state) {
                is Writing -> state.owner === owner
                else -> false
            }
        }

    private suspend fun acquireSlowPath() = suspendCancellableCoroutineReusable<Unit> sc@{ cont ->
        while (true) {
            if (addAcquireReadToQueue(cont)) return@sc

            if (tryAcquireForReading()) {
                cont.resume(Unit, onCancellationRelease)
                return@sc
            }
        }
    }

    private suspend fun lockSlowPath(owner: Any?) = suspendCancellableCoroutineReusable<Unit> sc@{ cont ->
        val acquirer = WritingAcquirer(if (owner == null) WRITING_EMPTY else Writing(owner), cont)

        while (true) {
            if (addLockWriteToQueue(acquirer)) return@sc

            if (tryLockForWriting(owner)) {
                cont.resume(Unit) { unlock(owner) }
                return@sc
            }
        }
    }

    override fun releaseFromReading() {
        _state.loop { state ->
            when (state) {
                is Int -> {
                    check(state < permits) { "The number of released permits cannot be greater than $permits" }

                    if (_state.compareAndSet(state, state + 1)) {
                        tryResumeNextFromQueue()
                        return
                    }
                }

                is Writing -> error("Cannot release a permit while writing")
                else -> error("Illegal state $state")
            }
        }
    }

    override fun unlockFromWriting(owner: Any?) {
        _state.loop { state ->
            when (state) {
                is Writing -> {
                    if (owner != null)
                        check(state.owner === owner) { "ReadWriteLock is locked by ${state.owner} but expected $owner" }

                    if (_state.compareAndSet(state, permits)) {
                        _queuedLocks.update { cur ->
                            check(cur > 0) { "Cannot have less than 0 locks!" }
                            cur - 1
                        }

                        tryResumeNextFromQueue()
                        return
                    }
                }

                is Int -> error("Cannot unlock while reading")
                else -> error("Illegal state $state")
            }
        }
    }

    /**
     * Returns `false` if the received permit cannot be used and the calling operation should restart.
     */
    private fun addAcquireReadToQueue(cont: CancellableContinuation<Unit>): Boolean {
        val curTail = this.tail.value
        val enqIdx = enqIdx.getAndIncrement()
        val segment = this.tail.findSegmentAndMoveForward(
            id = enqIdx / SEGMENT_SIZE, startFrom = curTail,
            createNewSegment = ::createSegment
        ).segment // cannot be closed
        val i = (enqIdx % SEGMENT_SIZE).toInt()
        // the regular (fast) path -- if the cell is empty, try to install continuation
        if (segment.cas(i, null, cont)) { // installed continuation successfully
            cont.invokeOnCancellation(CancelReadWriteLockAcquisitionHandler(segment, i).asHandler)
            return true
        }
        // On CAS failure -- the cell must be either PERMIT or BROKEN
        // If the cell already has PERMIT from tryResumeNextFromQueue, try to grab it
        if (segment.cas(i, PERMIT, TAKEN)) { // took permit thus eliminating acquire/release pair
            /// We need to double check that we can actually run this continuation right now
            _state.loop { state ->
                when (state) {
                    is Int -> {
                        if (state <= 0) return false
                        if (_state.compareAndSet(state, state - 1)) {
                            /// This continuation is not yet published, but still can be cancelled via outer job
                            cont.resume(Unit, onCancellationRelease)
                            return true
                        }
                    }

                    is Writing -> return false
                    else -> error("Illegal state $state")
                }
            }
        }
        assert { segment.get(i) === BROKEN } // it must be broken in this case, no other way around it
        return false // broken cell, need to retry on a different cell
    }

    /**
     * Returns `false` if the received permit cannot be used and the calling operation should restart.
     */
    private fun addLockWriteToQueue(acquirer: WritingAcquirer): Boolean {
        val curTail = this.tail.value
        val enqIdx = enqIdx.getAndIncrement()
        val segment = this.tail.findSegmentAndMoveForward(
            id = enqIdx / SEGMENT_SIZE, startFrom = curTail,
            createNewSegment = ::createSegment
        ).segment // cannot be closed
        val i = (enqIdx % SEGMENT_SIZE).toInt()
        // the regular (fast) path -- if the cell is empty, try to install continuation
        if (segment.cas(i, null, acquirer)) { // installed continuation successfully
            acquirer.continuation.invokeOnCancellation(CancelReadWriteLockAcquisitionHandler(segment, i).asHandler)
            return true
        }
        // On CAS failure -- the cell must be either PERMIT or BROKEN
        // If the cell already has PERMIT from tryResumeNextFromQueue, try to grab it
        if (segment.cas(i, PERMIT, TAKEN)) { // took permit thus eliminating acquire/release pair
            /// Check to make sure we can unlock

            _state.loop { state ->
                when (state) {
                    is Int -> {
                        if (state < permits) return false

                        val writing = if (acquirer.owner == null) WRITING_EMPTY else Writing(acquirer.owner)
                        if (_state.compareAndSet(state, writing)) {
                            /// This continuation is not yet published, but still can be cancelled via outer job
                            acquirer.continuation.resume(Unit) { unlockFromWriting(acquirer.owner) }
                            return true
                        }
                    }

                    is Writing -> return false
                    else -> error("Illegal state $state")
                }
            }
        }
        assert { segment.get(i) === BROKEN } // it must be broken in this case, no other way around it
        return false // broken cell, need to retry on a different cell
    }

    /*
        This is a bit more complicated than in `SemaphoreImpl` due to the sequential blocking nature
        of writing mutual exclusion.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryResumeNextFromQueue() {
        deqIdx.loop { idx ->
            // If we've gone beyond the enqueue index, there's nothing for us
            if (idx > enqIdx.value) return

            val curHead = this.head.value
            val id = idx / SEGMENT_SIZE
            val segment = this.head.findSegmentAndMoveForward(
                id, startFrom = curHead,
                createNewSegment = ::createSegment
            ).segment // cannot be closed
            segment.cleanPrev()
            if (segment.id > id) {
                // Increment and try again
                require(deqIdx.compareAndSet(idx, idx + 1))
                return@loop
            }
            val i = (idx % SEGMENT_SIZE).toInt()
            val cellState = segment.getAndSet(i, PERMIT) // set PERMIT and retrieve the prev cell state
            when {
                // If another call has taken this cell, let it handle it
                cellState === PERMIT -> return

                cellState === null -> {
                    // Acquire has not touched this cell yet, wait until it comes for a bounded time
                    // The cell state can only transition from PERMIT to TAKEN by addAcquireToQueue
                    repeat(MAX_SPIN_CYCLES) {
                        if (segment.get(i) === TAKEN) {
                            require(deqIdx.compareAndSet(idx, idx + 1))

                            return
                        }
                    }

                    require(deqIdx.compareAndSet(idx, idx + 1))

                    // Try to break the slot in order not to wait
                    if (!segment.cas(i, PERMIT, BROKEN)) return
                }

                // the acquire was already cancelled
                cellState === CANCELLED -> require(deqIdx.compareAndSet(idx, idx + 1))

                cellState is WritingAcquirer -> {
                    _state.loop inner@{ state ->
                        when (state) {
                            is Int -> {
                                // Not enough permits are available, wait
                                if (state < permits) return

                                val writing = if (cellState.owner == null) WRITING_EMPTY else Writing(cellState.owner)
                                if (_state.compareAndSet(state, writing)) {
                                    require(deqIdx.compareAndSet(idx, idx + 1))

                                    if (cellState.tryResumeLock()) return
                                    return@loop
                                }
                            }

                            // Already writing, can't resume yet
                            is Writing -> return
                            else -> error("Illegal state $state")
                        }
                    }
                }

                else -> {
                    _state.loop inner@{ state ->
                        when (state) {
                            is Int -> {
                                // No permits available
                                if (state <= 0) return

                                if (_state.compareAndSet(state, state - 1)) {
                                    require(deqIdx.compareAndSet(idx, idx + 1))

                                    if ((cellState as CancellableContinuation<Unit>).tryResumeAcquire()) {
                                        // If we only had one permit available, we've consumed it
                                        if (state <= 1) return
                                    }

                                    // Otherwise, we should try and keep resuming
                                    return@loop
                                }
                            }

                            is Writing -> return
                            else -> error("Illegal state $state")
                        }
                    }
                }
            }
        }
    }

    private fun CancellableContinuation<Unit>.tryResumeAcquire(): Boolean {
        val token = tryResume(Unit, null, onCancellationRelease) ?: return false
        completeResume(token)
        return true
    }

    private fun WritingAcquirer.tryResumeLock(): Boolean {
        val token = continuation.tryResume(Unit, null) { unlockFromWriting(owner) } ?: return false
        continuation.completeResume(token)
        return true
    }
}

private class CancelReadWriteLockAcquisitionHandler(
    private val segment: ReadWriteLockSegment,
    private val index: Int
) : CancelHandler() {
    override fun invoke(cause: Throwable?) {
        segment.cancel(index)
    }

    override fun toString() = "CancelReadWriteLockAcquisitionHandler[$segment, $index]"
}

private class WritingAcquirer(val owner: Any?, val continuation: CancellableContinuation<Unit>)

private fun createSegment(id: Long, prev: ReadWriteLockSegment?) = ReadWriteLockSegment(id, prev, 0)

private class ReadWriteLockSegment(id: Long, prev: ReadWriteLockSegment?, pointers: Int) :
    Segment<ReadWriteLockSegment>(id, prev, pointers) {
    val acquirers = atomicArrayOfNulls<Any?>(SEGMENT_SIZE)
    override val maxSlots: Int get() = SEGMENT_SIZE

    @Suppress("NOTHING_TO_INLINE")
    inline fun get(index: Int): Any? = acquirers[index].value

    @Suppress("NOTHING_TO_INLINE")
    inline fun set(index: Int, value: Any?) {
        acquirers[index].value = value
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun cas(index: Int, expected: Any?, value: Any?): Boolean = acquirers[index].compareAndSet(expected, value)

    @Suppress("NOTHING_TO_INLINE")
    inline fun getAndSet(index: Int, value: Any?) = acquirers[index].getAndSet(value)

    // Cleans the acquirer slot located by the specified index
    // and removes this segment physically if all slots are cleaned.
    fun cancel(index: Int) {
        // Clean the slot
        set(index, CANCELLED)
        // Remove this segment if needed
        onSlotCleaned()
    }

    override fun toString() = "ReadWriteLockSegment[id=$id, hashCode=${hashCode()}]"
}

@SharedImmutable
private val MAX_SPIN_CYCLES = systemProp("kotlinx.coroutines.ReadWriteLock.maxSpinCycles", 100)

@SharedImmutable
private val PERMIT = Symbol("PERMIT")

@SharedImmutable
private val TAKEN = Symbol("TAKEN")

@SharedImmutable
private val BROKEN = Symbol("BROKEN")

@SharedImmutable
private val CANCELLED = Symbol("CANCELLED")

@SharedImmutable
private val SEGMENT_SIZE = systemProp("kotlinx.coroutines.ReadWriteLock.segmentSize", 16)
