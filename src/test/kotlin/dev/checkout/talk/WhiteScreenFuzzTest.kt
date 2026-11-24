package dev.checkout.talk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded characterization test, not a regression test for a single bug: it runs the talk's
 * tiered loading and the single-level "every input starts as not-yet" combine through the
 * same 300 arrival schedules and asserts the invariants that held in every run.
 *
 * The corpus is the seeds themselves: each source independently never emits (15%) or emits
 * after a uniform 0..1000 ms virtual delay. Virtual time makes the run deterministic, so the
 * results are stable by construction and bounded by advanceTimeBy — no hangs, no flakiness.
 *
 * Regenerate the measurement (written to build/white-screen-fuzz.txt, gitignored) with:
 *   ./gradlew testDebugUnitTest --tests "*WhiteScreenFuzzTest*"
 * The committed snapshot of one run lives in docs/evidence/white-screen-fuzz.txt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WhiteScreenFuzzTest {

    private data class Main(val products: String, val user: String, val currency: String)

    private sealed interface State {
        data class Base(val main: Main) : State
        data class Enriched(val main: Main, val additional: String, val optional: String? = null) : State
    }

    private sealed interface Frame {
        data object Loading : Frame
        data class Content(
            val products: String,
            val user: String,
            val currency: String,
            val deliveryPrices: String?,
            val promos: String?,
        ) : Frame
    }

    private class Sources(
        val products: Flow<String>,
        val user: Flow<String>,
        val currency: Flow<String>,
        val deliveryPrices: Flow<String>,
        val promos: Flow<String>,
    )

    private fun source(delayMs: Long?, name: String): Flow<String> = flow {
        if (delayMs == null) awaitCancellation() else {
            delay(delayMs)
            emit(name)
        }
    }

    /** Faithful to WhiteScreenTest: sequential first() awaits, and loadAdditional() per optional emission. */
    private suspend fun collectTiered(src: Sources, out: MutableList<Pair<State, Long>>, now: () -> Long) {
        val main = Main(
            products = src.products.first(),
            user = src.user.first(),
            currency = src.currency.first(),
        )
        out += State.Base(main) to now()
        val additional = src.deliveryPrices.first()
        out += State.Enriched(main, additional) to now()
        src.promos.collect { promo ->
            val refreshed = src.deliveryPrices.first()
            out += State.Enriched(main, refreshed, promo) to now()
        }
    }

    private fun frames(src: Sources): Flow<Frame> = combine(
        src.products.notYet(),
        src.user.notYet(),
        src.currency.notYet(),
        src.deliveryPrices.notYet(),
        src.promos.notYet(),
    ) { products, user, currency, deliveryPrices, promos ->
        if (products == null || user == null || currency == null) Frame.Loading
        else Frame.Content(products, user, currency, deliveryPrices, promos)
    }.distinctUntilChanged()

    private fun <T : Any> Flow<T>.notYet(): Flow<T?> = onStart<T?> { emit(null) }

    private data class Metrics(
        val seed: Long,
        val tieredCount: Int,
        val tieredFirstMs: Long,          // -1 when tiered emitted nothing
        val tieredEnriched: Boolean,
        val combineCount: Int,
        val combineFirstMs: Long,
        val combineFirstContentMs: Long,  // -1 when combine never reached Content
        val combineLoadingCount: Int,
        val silentMandatory: Boolean,
    )

    private fun scenario(seed: Long): Metrics {
        lateinit var result: Metrics
        runTest {
            val scheduler = testScheduler
            val now = { scheduler.currentTime }
            val random = Random(seed)
            val delays = List(5) {
                if (random.nextInt(100) < 15) null else random.nextLong(0, 1001)
            }
            val src = Sources(
                products = source(delays[0], "products"),
                user = source(delays[1], "user"),
                currency = source(delays[2], "RUB"),
                deliveryPrices = source(delays[3], "deliveryPrices"),
                promos = source(delays[4], "promo"),
            )
            val silentMandatory = delays[0] == null || delays[1] == null || delays[2] == null

            val tiered = mutableListOf<Pair<State, Long>>()
            backgroundScope.launch { collectTiered(src, tiered, now) }

            val combineOut = mutableListOf<Pair<Frame, Long>>()
            frames(src).onEach { combineOut += it to now() }.launchIn(backgroundScope)

            advanceTimeBy(10_000)
            runCurrent()

            result = Metrics(
                seed = seed,
                tieredCount = tiered.size,
                tieredFirstMs = tiered.firstOrNull()?.second ?: -1,
                tieredEnriched = tiered.any { it.first is State.Enriched },
                combineCount = combineOut.size,
                combineFirstMs = combineOut.firstOrNull()?.second ?: -1,
                combineFirstContentMs = combineOut.firstOrNull { it.first is Frame.Content }?.second ?: -1,
                combineLoadingCount = combineOut.count { it.first is Frame.Loading },
                silentMandatory = silentMandatory,
            )
        }
        return result
    }

    private fun stats(name: String, values: List<Long>): String {
        if (values.isEmpty()) return "$name: n=0"
        val sorted = values.sorted()
        fun pct(p: Double) = sorted[(sorted.size * p).toInt().coerceAtMost(sorted.size - 1)]
        val mean = values.average()
        return "$name: n=${values.size} mean=${"%.0f".format(mean)} p50=${pct(0.5)} p90=${pct(0.9)} max=${sorted.last()}"
    }

    @Test
    fun `tiered vs single-level combine over seeded arrival schedules`() {
        val results = (0L until 300).map { scenario(it) }

        // Both approaches agree on whether content is reachable at all: exactly the
        // scenarios with a silent mandatory source never produce content.
        results.forEach {
            assertEquals(it.silentMandatory, it.tieredFirstMs < 0, "seed ${it.seed}: tiered content mismatch")
            assertEquals(it.silentMandatory, it.combineFirstContentMs < 0, "seed ${it.seed}: combine content mismatch")
        }

        // The difference is in what the waiting looks like: tiered emits nothing at all
        // while a mandatory source is silent; combine emits Loading from t=0 onward.
        val silentMandatory = results.filter { it.silentMandatory }
        assertTrue(silentMandatory.isNotEmpty(), "corpus lost its silent-mandatory scenarios")
        silentMandatory.forEach {
            assertEquals(0, it.tieredCount, "seed ${it.seed}: tiered emitted states despite silent mandatory")
            assertTrue(it.combineLoadingCount > 0, "seed ${it.seed}: combine showed no Loading despite silent mandatory")
        }
        results.forEach { assertEquals(0L, it.combineFirstMs, "seed ${it.seed}: combine's first frame was not at t=0") }

        // Where both reach content, combine is never later: its mandatory inputs are
        // collected in parallel (first content at max delay), tiered awaits them
        // sequentially (first content at the sum of delays).
        val bothContent = results.filter { it.tieredFirstMs >= 0 && it.combineFirstContentMs >= 0 }
        val violations = bothContent.count { it.combineFirstContentMs > it.tieredFirstMs }
        assertTrue(violations == 0, "combine first-content later than tiered in $violations scenarios")

        val report = buildString {
            appendLine("white-screen fuzz: 300 seeds, per-source delay = 15% never / else U(0,1000)ms virtual")
            appendLine("scenarios where a mandatory source never emits: ${results.count { it.silentMandatory }}")
            appendLine("  tiered emitted zero states (white screen persists): ${silentMandatory.count { it.tieredCount == 0 }}")
            appendLine("  combine emitted Loading frames: ${silentMandatory.count { it.combineLoadingCount > 0 }}")
            appendLine()
            appendLine(stats("tiered first-content ms", bothContent.map { it.tieredFirstMs }))
            appendLine(stats("combine first-content ms", bothContent.map { it.combineFirstContentMs }))
            appendLine("combine earlier in ${bothContent.count { it.combineFirstContentMs < it.tieredFirstMs }}/${bothContent.size} scenarios where both reached content; violations (combine later): $violations")
            appendLine()
            appendLine(stats("tiered state count", results.map { it.tieredCount.toLong() }))
            appendLine(stats("combine frame count", results.map { it.combineCount.toLong() }))
            appendLine("tiered reached Enriched in ${results.count { it.tieredEnriched }}/${results.size}")
            appendLine()
            appendLine("per-scenario: seed tieredCount tieredFirstMs tieredEnriched combineCount combineFirstMs combineFirstContentMs combineLoading silentMandatory")
            results.forEach {
                appendLine("${it.seed} ${it.tieredCount} ${it.tieredFirstMs} ${it.tieredEnriched} ${it.combineCount} ${it.combineFirstMs} ${it.combineFirstContentMs} ${it.combineLoadingCount} ${it.silentMandatory}")
            }
        }
        File("build/white-screen-fuzz.txt").writeText(report)
    }
}
