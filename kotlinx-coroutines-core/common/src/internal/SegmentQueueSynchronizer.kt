/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.CancellationMode.*
import kotlinx.coroutines.internal.SegmentQueueSynchronizer.ResumeMode.*
import kotlinx.coroutines.sync.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.native.concurrent.*

/**
 * [SegmentQueueSynchronizer] is an abstraction for implementing _fair_ synchronization and communication primitives.
 * Essentially, It maintains a FIFO queue of waiting requests and provides two main functions:
 *   - [suspend] stores the specified waiter into the queue, and
 *   - [resume] tries to retrieve and resume the first waiter, passing the specified value.
 * The key advantage of these semantics is that it allows to invoke [resume] before [suspend], and synchronization
 * primitives can leverage such races. For example, our [Semaphore] implementation actively uses this property
 * for better performance.
 *
 * One useful mental image of [SegmentQueueSynchronizer] is an infinite array with two counters: one references the
 * next cell in which new waiter is enqueued as a part of [suspend] call, and another references the next cell
 * for [resume]. The intuition is that [suspend] increments its counter and stores the continuation in the corresponding
 * cell. Likewise, [resume] increments its counter, visits the corresponding cell, and completes the stored
 * continuation with the specified value. However, [resume] may come to the cell before [suspend], so it finds the cell
 * in the empty state. To solve this race, we introduce two [resumption modes][ResumeMode]: synchronous and asynchronous.
 * In both case, [resume] puts the value into the empty cell, and then either finished immediately in the [asynchronous][ASYNC]
 * resumption mode, or waits until the value is taken by a concurrent [suspend] in the [synchronous][SYNC] one.
 * In the latter case, if the value is not taken within a bounded time, [resume] marks the cell as _broken_.
 * Thus, both this [resume] and the corresponding [suspend] fail. The intuition is that allowing for broken cells keeps
 * the balance of pairwise operations, such as [acquire()][Semaphore.acquire] and [release()][Semaphore.release],
 * so they should simply restart. This way, we can guarantee wait-freedom for [suspend] and [resume] with the
 * [asynchronous][ASYNC] mode, and obstruction-freedom with the [synchronous][SYNC] mode.
 *
 * **CANCELLATION**
 *
 * Since [suspend] can store [CancellableContinuation]-s, it is possible for [resume] to fail if the completing continuation
 * is already cancelled. In this case, most of the algorithms retry the whole operation. In [Semaphore], for example,
 * when [Semaphore.release] fails on the next waiter resumption, it logically provides the permit to the waiter but takes it back
 * after if the waiter is canceled. In the latter case, the permit should be released again so the operation restarts.
 *
 * However, the cancellation logic above requires to process all canceled cells. Thus, the operation that resumes waiters
 * (e.g., `release` in `Semaphore`) works in linear time in the number of consecutive canceled waiters. This way,
 * if N coroutines come to a semaphore, suspend, and cancel, the following `release` works in O(N) since it should
 * process all these N cells (and increment the counter of available permits in our implementation). While the complexity
 * is amortized by cancellations, it would be better to make such operations as `release` in `Semaphore` work predictably
 * fast, independently on the number of cancelled requests.
 *
 * The main idea to improve cancellation is skipping these `CANCELLED` cells so [resume] always succeeds
 * if no elimination happens (remember that [resume] may fail in the [synchronous][SYNC] resumption mode if
 * it finds the cell empty). Additionally, a cancellation handler that modifies the data structure consequently
 * should be specified (in [Semaphore] it should increment the available permits counter back). Thus, we support
 * several cancellation policies in [SegmentQueueSynchronizer]. The [SIMPLE] cancellation mode is already described above
 * and used by default. In this mode, [resume] fails if it finds the cell in the `CANCELLED` state or if the waiter resumption
 * (see [CancellableContinuation.tryResume]) does not succeed. As we discussed, these failures are typically handled
 * by re-starting the whole operation from the beginning. With the other two smart cancellation modes,
 * [SMART] and [SMART], [resume] skips `CANCELLED` cells (the cells where waiter resumption failed are also
 * considered as `CANCELLED`). This way, even if a million of canceled continuations are stored in [SegmentQueueSynchronizer],
 * one [resume] invocation is sufficient to pass the value to a waiter since it skips all these canceled waiters.
 * However, these modes provide less intuitive contracts and require users to write more complicated code;
 * the desuspendSegments are described further.
 *
 * The main issue with skipping `CANCELLED` cells in [resume] is that it can become illegal to put the value into
 * the next cell. Consider the following execution: [suspend] is called, then [resume] starts, but the suspended
 * waiter becomes canceled. This way, no waiter is waiting in [SegmentQueueSynchronizer] anymore. Thus, if [resume]
 * skips this canceled cell, puts the value into the next empty cell, and completes, the data structure's state becomes
 * incorrect. Instead, the value provided by this [resume] should be refused and returned to the outer data structure.
 * There is no way for [SegmentQueueSynchronizer] to decide whether the value should be refused automatically.
 * Instead, users should implement a cancellation handler by overriding the [onCancellation] function, which returns
 * `true` if the cancellation completes successfully and `false` if the [resume] that will come to this cell
 * should be refused. In the latter case, the [resume] that comes to this cell refuses its value by invoking
 * [tryReturnRefusedValue] to return it back to the outer data structure. However, it is possible for
 * [tryReturnRefusedValue] to fail, and [returnValue] is called in this case. Typically, this [returnValue] function
 * coincides with the one that resumes waiters (e.g., with [release][Semaphore.release] in [Semaphore]).
 * The difference between [SMART] and [SMART] modes is that in the [SMART] mode, a [resume] that comes
 * to a cell with a canceled waiter waits in a spin-loop until the cancellation handler is invoked and the cell
 * is moved to either `CANCELLED` or `REFUSE` state. In contrast, in the [SMART] mode, [resume] replaces
 * the canceled waiter with the value of this resumption and finishes immediately -- the cancellation handler
 * completes this [resume] eventually. This way, in [SMART] mode, the value passed to [resume] can be out
 * of the data structure for a while but is guaranteed to be processed eventually.
 *
 * To support prompt cancellation, [SegmentQueueSynchronizer] returns the value back to the data structure by calling
 * [returnValue] if the continuation is cancelled while dispatching. Typically, [returnValue] delegates to the operation
 * that calls [resume], such as [release][Semaphore.release] in [Semaphore].
 *
 * Here is a state machine for cells. Note that only one [suspend] and at most one [resume] can deal with each cell.
 *
 *  +-------+   `suspend` succeeds.   +--------------+  `resume` is   +---------------+  store `RESUMED` to   +---------+  ( `cont` HAS BEEN   )
 *  |  NULL | ----------------------> | cont: active | -------------> | cont: resumed | --------------------> | RESUMED |  ( RESUMED AND THIS  )
 *  +-------+                         +--------------+  successful.   +---------------+  avoid memory leaks.  +---------+  ( `resume` SUCCEEDS )
 *     |                                     |
 *     |                                     | The continuation
 *     | `resume` comes to                   | is cancelled.
 *     | the cell before                     |
 *     | `suspend` and puts                  V                                                                          ( THE CORRESPONDING `resume` SHOULD BE    )
 *     | the element into               +-----------------+    The concurrent `resume` should be refused,    +--------+ ( REFUSED AND `tryReturnRefusedValue`, OR )
 *     | the cell, waiting for          | cont: cancelled | -----------------------------------------------> | REFUSE | ( `returnValue` IF IT FAILS, IS USED TO   )
 *     | `suspend` if the resume        +-----------------+        `onCancellation` returned `false`.        +--------+ ( RETURN THE VALUE BACK TO THE OUTER      )
 *     | mode is `SYNC`.                     |         \                                                     ^          ( SYNCHRONIZATION PRIMITIVE               )
 *     |                                     |          \                                                    |
 *     |        Mark the cell as `CANCELLED` |           \                                                   |
 *     |         if the cancellation mode is |            \  `resume` delegates its completion to            | `onCancellation` returned `false,
 *     |        `SIMPLE` or `onCancellation` |             \   the concurrent cancellation handler if        | mark the state accordingly and
 *     |                    returned `true`. |              \   `SMART` cancellation mode is used.     | complete the hung `resume`.
 *     |                                     |               +------------------------------------------+    |
 *     |                                     |                                                           \   |
 *     |    (    THE CONTINUATION IS )       V                                                            V  |
 *     |    ( CANCELLED AND `resume` ) +-----------+                                                     +-------+
 *     |    (    FAILS IN THE SIMPLE ) | CANCELLED | <-------------------------------------------------- | value |
 *     |    (   CANCELLATION MODE OR ) +-----------+   Mark the cell as `CANCELLED` if `onCancellation`  +-------+
 *     |    (   SKIPS THIS CELL WITH )              returned true, complete the hung `resume` accordingly.
 *     |    (     SMART CANCELLATION )
 *     |
 *     |
 *     |            `suspend` gets   +-------+  ( RENDEZVOUS HAPPENED, )
 *     |         +-----------------> | TAKEN |  ( BOTH `resume` AND    )
 *     V         |   the element.    +-------+  ( `suspend` SUCCEED    )
 *  +-------+    |
 *  | value | --<
 *  +-------+   |
 *              | `tryResume` has waited a bounded time,  +--------+
 *              +---------------------------------------> | BROKEN | (BOTH `suspend` AND `resume` FAIL)
 *                     but `suspend` has not come.        +--------+
 *
 *
 * ** INFINITE ARRAY IMPLEMENTATION **
 *
 * The last open question is how to emulate the infinite array used in the mental image described above. Notice that
 * all cells are processed in sequential order. Therefore, the algorithm requires having access only to the cells
 * between the counters for [resume] and [suspend], and does not need to store an infinite array of cells.
 *
 * To make the implementation efficient we maintain a linked list of [segments][SQSSegment], see the basic [Segment]
 * class and the corresponding source file for desuspendSegments. In short, each segment has a unique id, and can be seen as
 * a node in a Michael-Scott queue. Following this structure, we can maintain the cells that are in the current active
 * range (between the counters), and access the cells similarly to an array. Specifically, we change the current working
 * segment once every [SEGMENT_SIZE] operations, where [SEGMENT_SIZE] is the number of cells stored in each segment.
 * It is worth noting that this linked list of segments does not store the ones full of cancelled cells; thus, avoiding
 * memory leaks and guaranteeing constant time complexity for [resume], even when the [smart cancellation][SMART] is used.
 */
internal abstract class SegmentQueueSynchronizer<T : Any> {
    private val resumeSegment: AtomicRef<SQSSegment>
    private val resumeIdx = atomic(0L)
    private val suspendSegment: AtomicRef<SQSSegment>
    private val suspendIdx = atomic(0L)

    init {
        val s = SQSSegment(0, null, 2)
        resumeSegment = atomic(s)
        suspendSegment = atomic(s)
    }

    /**
     * Specifies whether [resume] should work in
     * [synchronous][SYNC] or [asynchronous][ASYNC] mode.
     */
    protected open val resumeMode: ResumeMode get() = SYNC

    /**
     * Specifies whether [resume] should fail on cancelled waiters ([SIMPLE]),
     * or skip them in either [synchronous][SMART] or [asynchronous][SMART]
     * way. In the [asynchronous][SMART] mode, [resume] may pass the element to the
     * cancellation handler in order not to wait, so the element can be "hung"
     * for a while, but it is guaranteed that the element will be processed eventually.
     */
    protected open val cancellationMode: CancellationMode get() = SIMPLE

    /**
     * This function is invoked when waiter is cancelled and smart
     * cancellation mode is used (so cancelled cells are skipped by
     * [resume]). Typically, this function performs the logical cancellation.
     * It returns `true` if the cancellation succeeds and the cell can be
     * marked as [CANCELLED]. This way, [resume] skips this cell and passes
     * the value to another waiter in the waiting queue. However, if the [resume]
     * that comes to this cell should be refused, [onCancellation] should return false.
     * In this case, [tryReturnRefusedValue] is invoked with the value of this [resume],
     * following by [returnValue] if [tryReturnRefusedValue] fails.
     */
    protected open fun onCancellation() : Boolean = false

    /**
     * This function specifies how the value refused
     * by this [SegmentQueueSynchronizer] should
     * be returned back to the data structure. It
     * returns `true` on success or `false` if the
     * attempt fails, so [returnValue] should
     * be used to complete the returning.
     */
    protected open fun tryReturnRefusedValue(value: T): Boolean = true

    /**
     * This function specifies how the value from
     * a failed [resume] should be returned back to
     * the data structure. It is typically the function
     * that invokes [resume] (e.g., [release][Semaphore.release]
     * in [Semaphore]).
     *
     * This function is invoked when [onCancellation] returns `false`
     * and the following [tryReturnRefusedValue] fails, or when prompt
     * cancellation occurs.
     */
    protected open fun returnValue(value: T) {}

    /**
     * This is a shortcut for [tryReturnRefusedValue] and
     * the following [returnValue] invocation on failure.
     */
    private fun returnRefusedValue(value: T) {
        if (tryReturnRefusedValue(value)) return
        returnValue(value)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun suspend(cont: Continuation<T>): Boolean {
        // Increment `suspendIdx` and find the segment
        // with the corresponding id. It is guaranteed
        // that this segment is not removed since at
        // least the cell for this [suspend] invocation
        // is not in the `CANCELLED` state.
        val curSuspendSegm = this.suspendSegment.value
        val suspendIdx = suspendIdx.getAndIncrement()
        val segment = this.suspendSegment.findSegmentAndMoveForward(id = suspendIdx / SEGMENT_SIZE, startFrom = curSuspendSegm,
            createNewSegment = ::createSegment).segment
        assert { segment.id == suspendIdx / SEGMENT_SIZE }
        // Try to install the continuation in the cell,
        // this is the regular path.
        val i = (suspendIdx % SEGMENT_SIZE).toInt()
        if (segment.cas(i, null, cont)) {
            // The continuation is successfully installed, and
            // `resume` cannot break the cell now, so this
            // suspension is successful.
            // Add a cancellation handler if required and finish.
            if (cont is CancellableContinuation<*>) {
                cont.invokeOnCancellation(SQSCancellationHandler(segment, i).asHandler)
            }
            return true
        }
        // The continuation installation has failed. This happens
        // if a concurrent `resume` came earlier to this cell and put
        // its value into it. Note that in `SYNC` resumption mode
        // this concurrent `resume` can mark the cell as broken.
        //
        // Try to grab the value if the cell is not in the `BROKEN` state.
        val value = segment.get(i)
        if (value !== BROKEN && segment.cas(i, value, TAKEN)) {
            // The elimination is successfully performed,
            // resume the continuation with the value and complete.
            value as T
            when (cont) {
                is CancellableContinuation<*> -> {
                    cont as CancellableContinuation<T>
                    cont.resume(value, { returnValue(value) }) // TODO do we really need this?
                }
                else -> {
                    cont.resume(value)
                }
            }
            return true
        }
        // The cell is broken, this can happen only in `SYNC` resumption mode.
        assert { resumeMode == SYNC && segment.get(i) === BROKEN }
        return false
    }

    /**
     * Tries to resume the next waiter and returns `true` if
     * the resumption succeeds. However, it can fail due to
     * several reasons. First, if the [resumption mode][resumeMode]
     * is [synchronous][SYNC], this [resume] invocation may come
     * before [suspend] and mark the cell as [broken][BROKEN];
     * `false` is returned in this case. Another reason for [resume]
     * to fail is waiter cancellation if the [simple cancellation mode][SIMPLE]
     * is used.
     *
     * Note that when [smart cancellation][SMART] is used, [resume] skips
     * cancelled waiters and can fail only in case of unsuccessful elimination
     * due to [synchronous][SYNC] resumption mode.
     */
    fun resume(value: T): Boolean {
        // Should we skip cancelled cells?
        val skipCancelled = cancellationMode != SIMPLE
        while (true) {
            // Try to resume the next waiter, adjust [resumeIdx] if
            // cancelled cells should be skipped anyway.
            when (tryResumeImpl(value, adjustDeqIdx = skipCancelled)) {
                TRY_RESUME_SUCCESS -> return true
                TRY_RESUME_FAIL_CANCELLED -> if (!skipCancelled) return false
                TRY_RESUME_FAIL_BROKEN -> return false
            }
        }
    }

    /**
     * Tries to resume the next waiter, and returns [TRY_RESUME_SUCCESS] on
     * success, [TRY_RESUME_FAIL_CANCELLED] if the next waiter is cancelled,
     * or [TRY_RESUME_FAIL_BROKEN] if the next cell is marked as broken by
     * this [tryResumeImpl] invocation due to the [SYNC] resumption mode.
     *
     * In the smart cancellation modes ([SMART] and [SMART]) the
     * cells marked as [cancelled][CANCELLED] should be skipped, so
     * there is no need to increment [resumeIdx] one-by-one if there is a
     * removed segment (logically full of [cancelled][CANCELLED] cells);
     * it is faster to point [resumeIdx] to the first possibly non-cancelled
     * cell instead, i.e. to the first segment id multiplied by the
     * [segment size][SEGMENT_SIZE].
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryResumeImpl(value: T, adjustDeqIdx: Boolean): Int {
        // Check that `adjustDeqIdx` is `false`
        // in the simple cancellation mode.
        assert { !(cancellationMode == SIMPLE && adjustDeqIdx) }
        // Increment `resumeIdx` and find the first segment with
        // the corresponding or higher (if the required segment
        // is physically removed) id.
        val curResumeSegm = this.resumeSegment.value
        val resumeIdx = resumeIdx.getAndIncrement()
        val id = resumeIdx / SEGMENT_SIZE
        val segment = this.resumeSegment.findSegmentAndMoveForward(id, startFrom = curResumeSegm,
            createNewSegment = ::createSegment).segment
        // The previous segments can be safely collected
        // by GC, clean the pointer to them.
        segment.cleanPrev()
        // Is the required segment physically removed?
        if (segment.id > id) {
            // Adjust `resumeIdx` to the first
            // non-removed segment if needed.
            if (adjustDeqIdx) adjustDeqIdx(segment.id * SEGMENT_SIZE)
            // The cell #resumeIdx is in the `CANCELLED` state,
            // return the corresponding failure.
            return TRY_RESUME_FAIL_CANCELLED
        }
        // Modify the cell according to the state machine,
        // all the transitions are performed atomically.
        val i = (resumeIdx % SEGMENT_SIZE).toInt()
        modify_cell@while (true) {
            val cellState = segment.get(i)
            when {
                // Is the cell empty?
                cellState === null -> {
                    // Try to perform an elimination by putting the
                    // value to the empty cell and wait until it is
                    // taken by a concurrent `suspend` in case of
                    // using the synchronous resumption mode.
                    if (!segment.cas(i, null, value)) continue@modify_cell
                    // Finish immediately in the async mode.
                    if (resumeMode == ASYNC) return TRY_RESUME_SUCCESS
                    // Wait for a concurrent `suspend`, which should mark
                    // the cell as taken, for a bounded time in a spin-loop.
                    repeat(MAX_SPIN_CYCLES) {
                        if (segment.get(i) === TAKEN) return TRY_RESUME_SUCCESS
                    }
                    // The value is still not taken, try to
                    // atomically mark the cell as broken.
                    // A failure indicates that the value is taken.
                    return if (segment.cas(i, value, BROKEN)) TRY_RESUME_FAIL_BROKEN else TRY_RESUME_SUCCESS
                }
                // Is the waiter cancelled?
                cellState === CANCELLED -> {
                    // Return the corresponding failure.
                    return TRY_RESUME_FAIL_CANCELLED
                }
                // Should the current `resume` be refused
                // by this `SegmentQueueSynchronizer`?
                cellState === REFUSE -> {
                    // This state should not occur
                    // in the simple cancellation mode.
                    assert { cancellationMode != SIMPLE }
                    // Return the refused value back to
                    // the data structure and succeed.
                    returnRefusedValue(value)
                    return TRY_RESUME_SUCCESS
                }
                // Does the cell store a cancellable continuation?
                cellState is CancellableContinuation<*> -> {
                    // Change the cell state to `RESUMED`, so
                    // the cancellation handler cannot be invoked.
                    if (!segment.cas(i, cellState, RESUMED)) continue@modify_cell
                    // Try to resume the continuation.
                    val token = (cellState as CancellableContinuation<T>).tryResume(value, null, { returnValue(value) })
                    if (token != null) {
                        // `tryResume` has succeeded.
                        cellState.completeResume(token)
                    } else {
                        // `tryResume` has failed.
                        // Fail the current `resume` in the simple cancellation mode.
                        if (cancellationMode === SIMPLE)
                            return TRY_RESUME_FAIL_CANCELLED
                        // In smart cancellation mode, the cancellation
                        // handler should be invoked.
                        val cancelled = onCancellation()
                        if (cancelled) {
                            // Try to resume the next waiter. However,
                            // it can fail dur to synchronous mode.
                            // Return the value to the data structure
                            // in this case.
                            if (!resume(value)) returnValue(value)
                        } else {
                            // The value is refused, return
                            // it to the data structure.
                            returnRefusedValue(value)
                        }
                    }
                    // Once the state is changed to `RESUMED`, this
                    // `resume` is considered as successful. Note that
                    // possible cancellation is properly handled above,
                    // so it does not break this `resume`.
                    return TRY_RESUME_SUCCESS
                }
                cellState === CANCELLING -> {
                    // Fail in the simple cancellation mode.
                    if (cancellationMode == SIMPLE) return TRY_RESUME_FAIL_CANCELLED
                    // In the smart cancellation mode this cell
                    // can be either skipped (if it is going to
                    // be marked as cancelled) or this `resume`
                    // should be refused.
                    //
                    // In the `SYNC` resumption mode, `resume(..)`
                    // waits in a an infinite spin-loop until
                    // the state of this cell is changed to
                    // either `CANCELLED` or `REFUSE`.
                    if (resumeMode == SYNC) continue@modify_cell
                    // In the asynchronous resumption mode,
                    // `resume` replaces the cancelled continuation
                    // with the resumption value and completes.
                    // Thus, the concurrent cancellation handler
                    // detects this value and completes this `resume`.
                    // The continuation is cancelled but the
                    // cancellation handler is not completed yet.
                    val valueToStore: Any = if (value is Continuation<*>) WrappedContinuationValue(value) else value
                    if (segment.cas(i, cellState, valueToStore)) return TRY_RESUME_SUCCESS
                }
                // The cell stores a non-cancellable
                // continuation, we can simply resume it.
                cellState is Continuation<*> -> {
                    // Resume the continuation and mark the cell
                    // as `RESUMED` to avoid memory leaks.
                    segment.set(i, RESUMED)
                    (cellState as Continuation<T>).resume(value)
                    return TRY_RESUME_SUCCESS
                }
                else -> error("Unexpected cell state: $cellState")
            }
        }
    }

    /**
     * Updates [resumeIdx] to [newValue] if the current value is lower.
     * Thus, it is guaranteed that either the update is successfully
     * performed or the value of [resumeIdx] is greater or equal to [newValue].
     */
    private fun adjustDeqIdx(newValue: Long): Unit = resumeIdx.loop { cur ->
        if (cur >= newValue) return
        if (resumeIdx.compareAndSet(cur, newValue)) return
    }

    /**
     * These modes define the strategy that [resume] should
     * use if it comes to the cell before [suspend] and finds it empty.
     * In the [asynchronous][ASYNC] mode, it puts the value into the cell,
     * so [suspend] grabs it and immediately resumes without actual
     * suspension; in other words, an elimination happens in this case.
     * However, this strategy produces an incorrect behavior when used for some
     * data structures (e.g., for [tryAcquire][Semaphore.tryAcquire] in [Semaphore]),
     * so the [synchronous][SYNC] was introduced. Similarly to the asynchronous one,
     * [resume] puts the value into the cell, but do not finish right after that.
     * In opposite, it waits in a bounded spin-loop (see [MAX_SPIN_CYCLES]) until
     * the value is taken, and completes after that. If the value is not taken after
     * this spin-loop is finished, [resume] marks the cell as [broken][BROKEN]
     * and fails, so the corresponding [suspend] invocation finds the cell
     * [broken][BROKEN] and fails as well.
     */
    internal enum class ResumeMode { SYNC, ASYNC }

    /**
     * These modes define the mode that should be used for cancellation.
     * Essentially, there are two modes, simple and smart, which
     * specify whether [resume] should fail on cancelled waiters in the
     * [simple][SIMPLE] cancellation mode, or skip them in the [smart][SMART]
     * one. In the [asynchronous resumption mode][ResumeMode.ASYNC],
     * [resume] is eligible to put the element into the cell and complete,
     * the cancellation handler processes this element eventually. However,
     * the element can be "hung" for a while in this case.
     */
    internal enum class CancellationMode { SIMPLE, SMART }

    /**
     * This cancellation handler is invoked when
     * the waiter located by ```segment[index]```
     * is cancelled.
     */
    private inner class SQSCancellationHandler(
        private val segment: SQSSegment,
        private val index: Int
    ) : CancelHandler() {
        override fun invoke(cause: Throwable?) {
            // Invoke the cancellation handler
            // only if the state is not `RESUMED`.
            if (!segment.tryMarkCancelling(index)) return
            // Do we use simple or smart cancellation?
            if (cancellationMode === SIMPLE) {
                // In the simple cancellation mode the logic
                // is straightforward -- mark the cell as
                // cancelled to avoid memory leaks and complete.
                segment.markCancelled(index)
                return
            }
            // We are in a smart cancellation mode.
            // Perform the cancellation-related logic and
            // check whether the value of the `resume` that
            // comes to this cell should be processed in the
            // `SegmentQueueSynchronizer` or refused by it.
            val cancelled = onCancellation()
            if (cancelled) {
                // The cell should be considered as cancelled.
                // Mark the cell correspondingly and help a
                // concurrent `resume` to process its value if
                // needed (see `SMART` cancellation mode).
                val value = segment.markCancelled(index) ?: return
                if (value === REFUSE) return
                // Try to resume the next waiter with the value
                // provided by a concurrent `resume`.
                if (resume(value as T)) return
                // The resumption has been failed because of the
                // `SYNC` resume mode. Return the value back to
                // the original data structure.
                returnValue(value)
            } else {
                // The value of the `resume` that comes to this
                // cell should be refused by this `SegmentQueueSynchronizer`.
                // Mark the cell correspondingly and help a concurrent
                // `resume` to process its value if needed
                // (see `SMART` cancellation mode).
                val value = segment.markRefused(index) ?: return
                returnRefusedValue(value as T)
            }
        }

        override fun toString() = "SQSCancellationHandler[$segment, $index]"
    }

    override fun toString(): String {
        val waiters = ArrayList<String>()
        var curSegment = resumeSegment.value
        var curIdx = resumeIdx.value
        while (curIdx < max(suspendIdx.value, resumeIdx.value)) {
            val i = (curIdx % SEGMENT_SIZE).toInt()
            waiters += when {
                curIdx < curSegment.id * SEGMENT_SIZE -> "CANCELLED"
                curSegment.get(i) is Continuation<*> -> "<cont>"
                else -> curSegment.get(i).toString()
            }
            curIdx++
            if (curIdx == (curSegment.id + 1) * SEGMENT_SIZE)
                curSegment = curSegment.next ?: break
        }
        return "suspendIdx=${suspendIdx.value},resumeIdx=${resumeIdx.value},waiters=$waiters"
    }
}

private fun createSegment(id: Long, prev: SQSSegment?) = SQSSegment(id, prev, 0)

/**
 * The queue of waiters in [SegmentQueueSynchronizer]
 * is represented as a linked list of [SQSSegment].
 */
private class SQSSegment(id: Long, prev: SQSSegment?, pointers: Int) : Segment<SQSSegment>(id, prev, pointers) {
    private val waiters = atomicArrayOfNulls<Any?>(SEGMENT_SIZE)
    override val maxSlots: Int get() = SEGMENT_SIZE

    @Suppress("NOTHING_TO_INLINE")
    inline fun get(index: Int): Any? = waiters[index].value

    @Suppress("NOTHING_TO_INLINE")
    inline fun set(index: Int, value: Any?) {
        waiters[index].value = value
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun cas(index: Int, expected: Any?, value: Any?): Boolean = waiters[index].compareAndSet(expected, value)

    @Suppress("NOTHING_TO_INLINE")
    inline fun getAndSet(index: Int, value: Any?): Any? = waiters[index].getAndSet(value)

    /**
     * Marks the cell as cancelled and returns `null`, so the [resume]
     * that comes to this cell detects that it is in the `CANCELLED` state
     * and should fail or skip it depending on the cancellation mode.
     * However, in [SMART] cancellation mode [resume] that comes to the cell
     * with cancelled continuation asynchronously puts its value into the cell,
     * and the cancellation handler completes the resumption.
     * In this case, [markCancelled] returns this non-null value.
     *
     * If the whole segment contains [CANCELLED] markers after
     * this invocation, [onSlotCleaned] is invoked and this segment
     * is going to be removed if [resumeSegment][SegmentQueueSynchronizer.resumeSegment]
     * and [suspendSegment][SegmentQueueSynchronizer.suspendSegment] do not reference it.
     * Note that the segments that are not stored physically are still
     * considered as logically stored but being full of cancelled waiters.
     */
    fun markCancelled(index: Int): Any? = mark(index, CANCELLED).also {
        onSlotCleaned()
    }

    /**
     * In [SegmentQueueSynchronizer] we use different cancellation handlers for
     * normal and prompt cancellations. However, there is no way to split them
     * in the current [CancellableContinuation] API: the handler set by
     * [CancellableContinuation.invokeOnCancellation] is always invoked,
     * even on prompt cancellation. In order to guarantee that only one of
     * the handler is invoked (either the one installed by `invokeOnCancellation`
     * or the one passed in `tryResume`) we use a special intermediate state
     * `CANCELLING` for normal cancellation. Thus, if the state is already
     * `RESUMED`, then [tryMarkCancelling] returns `false` and the normal
     * cancellation handler (installed by `invokeOnCancellation`) is not
     * executed (we try to move the state to `CANCELLING` in the beginning).
     */
    fun tryMarkCancelling(index: Int): Boolean {
        while (true) {
            val cellState = get(index)
            when {
                cellState is CancellableContinuation<*> -> {
                    if (cas(index, cellState, CANCELLING)) return true
                }
                else -> {
                    if (cellState is Continuation<*>)
                        error("Only cancellable continuations can be cancelled, ${cellState::class.simpleName} is found")
                    else
                        error("Unexpected cell state: $cellState")
                }
            }
        }
    }

    /**
     * Marks the cell as refused and returns `null`, so
     * the [resume] that comes to the cell should notice
     * that its value is refused by the [SegmentQueueSynchronizer],
     * and [SegmentQueueSynchronizer.tryReturnRefusedValue]
     * is invoked in this case (if it fails, the value is put back via
     * [SegmentQueueSynchronizer.returnValue]). Since in [SMART]
     * cancellation mode [resume] that comes to the cell with cancelled
     * continuation asynchronously puts its value into the cell.
     * In this case, [markRefused] returns this non-null value.
     */
    fun markRefused(index: Int): Any? = mark(index, REFUSE)

    /**
     * Marks the cell with the specified [marker]
     * and returns `null` if the cell contains the
     * cancelled continuation. However, in the [SMART]
     * cancellation mode it is possible that [resume] comes
     * to the cell with cancelled continuation and asynchronously
     * puts its value into the cell, so the cancellation
     * handler decides whether this value should be used for
     * resuming the next waiter or be refused. In the latter case,
     * the corresponding non-null value is returned as a result.
     */
    private fun mark(index: Int, marker: Any?): Any? =
        when (val old = getAndSet(index, marker)) {
            // Did the cell contain already cancelled or cancelling continuation?
            CANCELLING -> null
            is Continuation<*> -> {
                assert { if (old is CancellableContinuation<*>) old.isCancelled else true }
                null
            }
            // Did the cell contain an asynchronously put value?
            // (both branches deal with values)
            is WrappedContinuationValue -> old.cont
            else -> old
        }

    override fun toString() = "SQSSegment[id=$id, hashCode=${hashCode()}]"
}

/**
 * In the [smart cancellation mode][SegmentQueueSynchronizer.CancellationMode.SMART]
 * it is possible for [resume] to come to a cell with cancelled continuation and
 * asynchronously put the resumption value into the cell, so the cancellation handler decides whether
 * this value should be used for resuming the next waiter or be refused. When this
 * value is a continuation, it is hard to distinguish it with the one related to the cancelled
 * waiter. To solve the problem, such values of type [Continuation] are wrapped with
 * [WrappedContinuationValue]. Note that the wrapper is required only in [SegmentQueueSynchronizer.CancellationMode.SMART]
 * mode and is used in the asynchronous race resolution logic between cancellation and [resume]
 * invocation; this way, it is used relatively rare.
 */
private class WrappedContinuationValue(val cont: Continuation<*>)

@SharedImmutable
private val SEGMENT_SIZE = systemProp("kotlinx.coroutines.sqs.segmentSize", 16)
@SharedImmutable
private val MAX_SPIN_CYCLES = systemProp("kotlinx.coroutines.sqs.maxSpinCycles", 100)
@SharedImmutable
private val TAKEN = Symbol("TAKEN")
@SharedImmutable
private val BROKEN = Symbol("BROKEN")
@SharedImmutable
private val CANCELLING = Symbol("CANCELLING")
@SharedImmutable
private val CANCELLED = Symbol("CANCELLED")
@SharedImmutable
private val REFUSE = Symbol("REFUSE")
@SharedImmutable
private val RESUMED = Symbol("RESUMED")

private const val TRY_RESUME_SUCCESS = 0
private const val TRY_RESUME_FAIL_CANCELLED = 1
private const val TRY_RESUME_FAIL_BROKEN = 2