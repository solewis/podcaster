package com.solewis.podcaster.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Points `Dispatchers.Main` at a test dispatcher, since `viewModelScope` uses
 * `Dispatchers.Main.immediate` and there is no main looper on the JVM.
 *
 * Run tests with `runTest(rule.dispatcher)` so the ViewModel and the test share one scheduler -
 * otherwise virtual time the test advances never reaches work the ViewModel launched, and the
 * search debounce in particular silently never fires.
 */
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Subscribes for the rest of the test, on a real dispatcher.
 *
 * Two reasons. A `stateIn(..., WhileSubscribed())` flow stays cold and pinned to its initial value
 * until something collects it, so without this every assertion reads a default `UiState`. And the
 * collector has to sit on a real thread: Room delivers its emissions from its own threads, so a
 * collector parked on the test dispatcher would only see them when the test happened to advance.
 */
fun TestScope.keepHot(vararg flows: Flow<*>) {
    flows.forEach { flow -> backgroundScope.launch(Dispatchers.Default) { flow.collect {} } }
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
