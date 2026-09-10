package com.solewis.podcaster.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import java.io.Closeable

/**
 * Points `Dispatchers.Main` at a test dispatcher, since `viewModelScope` uses
 * `Dispatchers.Main.immediate` and there is no main looper on the JVM.
 *
 * Run tests with `runTest(rule.dispatcher)` so the ViewModel and the test share one scheduler -
 * otherwise virtual time the test advances never reaches work the ViewModel launched, and the
 * search debounce in particular silently never fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        // Drain before resetting, or the suite fails somewhere else entirely.
        //
        // A `stateIn(viewModelScope, WhileSubscribed(5s))` sharing coroutine outlives the test
        // body: `runTest` cancels the subscribers, `WhileSubscribed` then parks for five seconds
        // of *virtual* time, and nothing is advancing the clock any more - so it is still alive
        // when the ViewModel store is cleared in `@After`. Cancelling it is not synchronous, and
        // its final dispatch goes to `Dispatchers.Main`, which by then has been reset. That throws
        // `DispatchException: Coroutine dispatcher Dispatchers.Main threw` on a background thread,
        // and the next test to call `runTest` is the one that reports it, as
        // `UncaughtExceptionsBeforeTest`. The blame lands on whichever test happens to be next,
        // which is why this looked like several unrelated flaky tests rather than one leak.
        //
        // Advancing to idle lets that parked delay expire and the coroutine finish here, while
        // Main still exists.
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
}

/**
 * Subscribes for the rest of the test, so a `stateIn(..., WhileSubscribed())` flow actually runs.
 * Without it such a flow stays cold and pinned to its initial value, and every assertion reads a
 * default `UiState`.
 *
 * On the test's own dispatcher, *not* `Dispatchers.Default`. It used to launch on Default, on the
 * reasoning that Room delivers emissions from its own threads and a collector on the test
 * dispatcher would only see them when the test advanced the clock. That reasoning does not hold
 * for an `UnconfinedTestDispatcher`, which is unconfined: a continuation resumes immediately on
 * whichever thread signalled it, Room's included. Nothing was waiting for the clock.
 *
 * What launching on Default did cause was the suite's flakiness. `WhileSubscribed` starts its
 * sharing coroutine on the first subscriber, and under an unconfined Main that start happens
 * inline on the subscriber's thread - so the `ProducerCoroutine` ended up living on a Default
 * worker while carrying `Dispatchers.Main` in its context. Cancelling it at teardown then
 * completed on that worker and dispatched to Main from there, racing [MainDispatcherRule]'s reset;
 * lose the race and it threw `DispatchException: Coroutine dispatcher Dispatchers.Main threw` on a
 * background thread, which the *next* test reported as `UncaughtExceptionsBeforeTest`. Measured at
 * roughly one failed run in two, always blaming an innocent test.
 *
 * Keeping the collector on the test dispatcher keeps that whole lifecycle on the test thread,
 * where the rule's drain can finish it deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepHot(vararg flows: Flow<*>) {
    flows.forEach { flow -> backgroundScope.launch { flow.collect {} } }
}

/**
 * Waits, in real time, for this flow to hold a value satisfying [predicate].
 *
 * `advanceUntilIdle()` is the wrong tool for anything downstream of Room: it drains the test
 * scheduler, which has no knowledge of the threads Room actually runs queries on, so it returns
 * while the query is still in flight. The hop to [Dispatchers.Default] is what makes the timeout
 * real - a `withTimeout` left on the test dispatcher would consume virtual time and expire at once.
 */
suspend fun <T> StateFlow<T>.awaitValue(
    timeoutMillis: Long = TIMEOUT_MILLIS,
    predicate: (T) -> Boolean
): T = withContext(Dispatchers.Default) {
    withTimeoutOrNull(timeoutMillis) { first(predicate) }
} ?: error("State never satisfied the predicate within ${timeoutMillis}ms. Last value: $value")

/** As [awaitValue], for a side effect that isn't exposed as a flow - a row written, a call recorded. */
suspend fun awaitTrue(
    what: String,
    timeoutMillis: Long = TIMEOUT_MILLIS,
    condition: suspend () -> Boolean
) {
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(POLL_MILLIS)
        }
    } ?: error("Timed out after ${timeoutMillis}ms waiting for: $what")
}

/** Lets already-scheduled work settle, for asserting that something did *not* happen. */
suspend fun CoroutineScope.settle() {
    withContext(Dispatchers.Default) { delay(SETTLE_MILLIS) }
}

private const val TIMEOUT_MILLIS = 5_000L
private const val POLL_MILLIS = 5L
private const val SETTLE_MILLIS = 150L

/**
 * Owns the ViewModels a test builds, so their `viewModelScope` is cancelled when the test ends.
 *
 * Without this they leak. A ViewModel's scope is never cancelled unless something clears it, so its
 * `stateIn(WhileSubscribed(5s))` sharing coroutine outlives the test still holding a pending
 * timeout - and when the next `Dispatchers.setMain`/`resetMain` runs, kotlinx-coroutines' guard
 * throws "Dispatchers.Main is used concurrently with setting it". That surfaces as an unrelated
 * test failing intermittently, which is the worst kind: it looks like the code under test.
 *
 * This alone was not enough, and for a while it looked as though it had been: clearing the store
 * *cancels* those coroutines but does not wait for them, so their completion could still land
 * after Main had been reset. See [keepHot] and [MainDispatcherRule] for the other two halves.
 *
 * `clear()` is the only public route to cancelling the scope - `ViewModel.clear()` itself is
 * internal to the library.
 */
class ViewModelHost : Closeable {

    private val store = ViewModelStore()
    private var next = 0

    fun <T : ViewModel> hosting(viewModel: T): T {
        store.put("viewModel${next++}", viewModel)
        return viewModel
    }

    override fun close() = store.clear()
}

