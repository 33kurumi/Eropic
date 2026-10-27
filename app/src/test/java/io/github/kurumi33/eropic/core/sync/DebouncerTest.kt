package io.github.kurumi33.eropic.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun burstCollapsesIntoOneTrigger() = runTest {
        var triggered = 0
        val debouncer = Debouncer(this, DEBOUNCE_MS) { triggered++ }

        debouncer.submit()
        advanceTimeBy(1000)
        debouncer.submit()
        advanceTimeBy(1000)
        runCurrent()
        // 距最后一次变化才 1000ms，还在窗口内
        assertEquals(0, triggered)

        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, triggered)
    }

    @Test
    fun cancelPreventsPendingTrigger() = runTest {
        var triggered = 0
        val debouncer = Debouncer(this, DEBOUNCE_MS) { triggered++ }

        debouncer.submit()
        advanceTimeBy(100)
        debouncer.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, triggered)
    }

    @Test
    fun twoSeparateBurstsTriggerTwice() = runTest {
        var triggered = 0
        val debouncer = Debouncer(this, DEBOUNCE_MS) { triggered++ }

        debouncer.submit()
        advanceTimeBy(1600)
        runCurrent()
        assertEquals(1, triggered)

        debouncer.submit()
        advanceTimeBy(1600)
        runCurrent()
        assertEquals(2, triggered)
    }

    private companion object {
        const val DEBOUNCE_MS = 1500L
    }
}
