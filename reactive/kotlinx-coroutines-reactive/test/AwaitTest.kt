/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.reactive

import kotlinx.coroutines.*
import org.junit.*

class AwaitTest: TestBase() {

    /** Tests that calls to [awaitFirst] (and, thus, to the rest of these functions) throw [CancellationException] when
     * their [Job] is cancelled. */
    @Test
    fun testAwaitCancellation() = runTest {
        expect(1)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                expect(2)
                publish<Int> { delay(Long.MAX_VALUE) }.awaitFirst()
            } catch (e: CancellationException) {
                expect(4)
                throw e
            }
        }
        expect(3)
        job.cancelAndJoin()
        finish(5)
    }

}