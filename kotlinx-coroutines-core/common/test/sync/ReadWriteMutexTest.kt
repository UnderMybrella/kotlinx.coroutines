package kotlinx.coroutines.sync

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadWriteMutexTest : TestBase() {

    @Test
    fun testSimpleRead() = runTest {
        val mutex = ReadWriteMutex(2)
        launch {
            expect(3)
            mutex.releaseFromReading()
            expect(4)
        }
        expect(1)
        mutex.acquireForReading()
        mutex.acquireForReading()
        expect(2)
        mutex.acquireForReading()
        finish(5)
    }

    @Test
    fun testSimpleWrite() = runTest {
        val mutex = ReadWriteMutex(2)
        expect(1)
        launch {
            expect(4)
            mutex.lockForWriting() // suspends
            expect(7) // now got lock
            mutex.unlockFromWriting()
            expect(8)
        }
        expect(2)
        mutex.lockForWriting() // locked
        expect(3)
        yield() // yield to child
        expect(5)
        mutex.unlockFromWriting()
        expect(6)
        yield() // now child has lock
        finish(9)
    }

    @Test
    fun testSimpleReadAsMutex() = runTest {
        val mutex = ReadWriteMutex(1)
        expect(1)
        launch {
            expect(4)
            mutex.acquireForReading() // suspends
            expect(7) // now got lock
            mutex.releaseFromReading()
            expect(8)
        }
        expect(2)
        mutex.acquireForReading() // locked
        expect(3)
        yield() // yield to child
        expect(5)
        mutex.releaseFromReading()
        expect(6)
        yield() // now child has lock
        finish(9)
    }

    @Test
    fun simpleReadWriteTest() = runTest {
        val mutex = ReadWriteMutex(2)
        expect(1)
        launch(coroutineContext) {
            expect(4)
            mutex.lockForWriting()
            expect(8)
            mutex.unlockFromWriting()
            expect(9)
        }
        launch(coroutineContext) {
            expect(5)
            mutex.acquireForReading()
            expect(11)
            mutex.releaseFromReading()
            expect(12)
        }
        expect(2)
        mutex.acquireForReading()
        expect(3)
        yield()
        expect(6)
        mutex.releaseFromReading()
        expect(7)
        yield()
        expect(10)
        yield()
        finish(13)
    }

    @Test
    fun tryAcquireForReadingTest() = runTest {
        val mutex = ReadWriteMutex(2)
        assertTrue(mutex.tryAcquireForReading())
        assertTrue(mutex.tryAcquireForReading())
        assertFalse(mutex.tryAcquireForReading())
        assertEquals(0, mutex.availablePermitsForReading)
        mutex.releaseFromReading()
        assertEquals(1, mutex.availablePermitsForReading)
        assertTrue(mutex.tryAcquireForReading())
        assertEquals(0, mutex.availablePermitsForReading)
    }

    @Test
    fun tryLockWriteTest() = runTest {
        val mutex = ReadWriteMutex(2)
        assertTrue(mutex.tryLockForWriting())
        assertFalse(mutex.tryAcquireForReading())
        assertTrue(mutex.isLockedForWriting)
        mutex.unlockFromWriting()
        assertFalse(mutex.isLockedForWriting)
        assertTrue(mutex.tryLockForWriting())
        assertTrue(mutex.isLockedForWriting)
    }

    @Test
    fun withPermitTest() = runTest {
        val mutex = ReadWriteMutex(1)
        assertEquals(1, mutex.availablePermitsForReading)
        mutex.withPermitForReading {
            assertEquals(0, mutex.availablePermitsForReading)
        }
        assertEquals(1, mutex.availablePermitsForReading)
    }

    @Test
    fun withLockTest() = runTest {
        val mutex = ReadWriteMutex(2)
        assertFalse(mutex.isLockedForWriting)
        mutex.withLockForWriting {
            assertTrue(mutex.isLockedForWriting)
        }
        assertFalse(mutex.isLockedForWriting)
    }

    @Test
    fun testUnconfinedStackOverflow() {
        val waiters = 10000
        val mutex = ReadWriteMutex(1, locked = true)
        var done = 0
        repeat(waiters) {
            GlobalScope.launch(Dispatchers.Unconfined) {  // a lot of unconfined waiters
                mutex.withLock {
                    done++
                }
            }
        }
        mutex.unlock() // should not produce StackOverflowError
        assertEquals(waiters, done)
    }

    @Test
    fun holdLock() = runTest {
        val mutex = ReadWriteMutex(1)
        val firstOwner = Any()
        val secondOwner = Any()

        // no lock
        assertFalse(mutex.holdsLock(firstOwner))
        assertFalse(mutex.holdsLock(secondOwner))

        // owner firstOwner
        mutex.lockForWriting(firstOwner)
        val secondLockJob = launch {
            mutex.lockForWriting(secondOwner)
        }

        assertTrue(mutex.holdsLock(firstOwner))
        assertFalse(mutex.holdsLock(secondOwner))

        // owner secondOwner
        mutex.unlockFromWriting(firstOwner)
        secondLockJob.join()

        assertFalse(mutex.holdsLock(firstOwner))
        assertTrue(mutex.holdsLock(secondOwner))

        mutex.unlockFromWriting(secondOwner)

        // no lock
        assertFalse(mutex.holdsLock(firstOwner))
        assertFalse(mutex.holdsLock(secondOwner))
    }

    @Test
    fun readFairnessTest() = runTest {
        val mutex = ReadWriteMutex(1)
        mutex.acquireForReading()
        launch(coroutineContext) {
            // first to acquire
            expect(2)
            mutex.acquireForReading() // suspend
            expect(6)
        }
        launch(coroutineContext) {
            // second to acquire
            expect(3)
            mutex.acquireForReading() // suspend
            expect(9)
        }
        expect(1)
        yield()
        expect(4)
        mutex.releaseFromReading()
        expect(5)
        yield()
        expect(7)
        mutex.releaseFromReading()
        expect(8)
        yield()
        finish(10)
    }

    @Test
    fun testCancellationReturnsReadPermitBack() = runTest {
        val mutex = ReadWriteMutex(1)
        mutex.acquireForReading()
        assertEquals(0, mutex.availablePermitsForReading)
        val job = launch {
            assertFalse(mutex.tryAcquireForReading())
            mutex.acquireForReading()
        }
        yield()
        job.cancelAndJoin()
        assertEquals(0, mutex.availablePermitsForReading)
        mutex.releaseFromReading()
        assertEquals(1, mutex.availablePermitsForReading)
    }

    @Test
    fun testCancellationDoesNotResumeWaitingReadAcquirers() = runTest {
        val mutex = ReadWriteMutex(1)
        mutex.acquireForReading()
        val job1 = launch { // 1st job in the waiting queue
            expect(2)
            mutex.acquireForReading()
            expectUnreached()
        }
        val job2 = launch { // 2nd job in the waiting queue
            expect(3)
            mutex.acquireForReading()
            expectUnreached()
        }
        expect(1)
        yield()
        expect(4)
        job2.cancel()
        yield()
        expect(5)
        job1.cancel()
        finish(6)
    }

    @Test
    fun testAcquiredPermitsForReading() = runTest {
        val mutex = ReadWriteMutex(5, acquiredPermits = 4)
        assertEquals(mutex.availablePermitsForReading, 1)
        mutex.acquireForReading()
        assertEquals(mutex.availablePermitsForReading, 0)
        assertFalse(mutex.tryAcquireForReading())
        mutex.releaseFromReading()
        assertEquals(mutex.availablePermitsForReading, 1)
        assertTrue(mutex.tryAcquireForReading())
    }

    @Test
    fun testReleaseAcquiredPermits() = runTest {
        val mutex = ReadWriteMutex(5, acquiredPermits = 4)
        assertEquals(mutex.availablePermitsForReading, 1)
        repeat(4) { mutex.releaseFromReading() }
        assertEquals(5, mutex.availablePermitsForReading)
        assertFailsWith<IllegalStateException> { mutex.releaseFromReading() }
        repeat(5) { assertTrue(mutex.tryAcquireForReading()) }
        assertFalse(mutex.tryAcquireForReading())
    }

    @Test
    fun testIllegalArguments() {
        assertFailsWith<IllegalArgumentException> { ReadWriteMutex(-1, 0) }
        assertFailsWith<IllegalArgumentException> { ReadWriteMutex(0, 0) }
        assertFailsWith<IllegalArgumentException> { ReadWriteMutex(1, -1) }
        assertFailsWith<IllegalArgumentException> { ReadWriteMutex(1, 2) }
        assertFailsWith<IllegalArgumentException> { ReadWriteMutex(1, 1, true) }
    }
}