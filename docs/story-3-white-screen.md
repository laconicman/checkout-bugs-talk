# Story 3: the white screen

Tests: [WhiteScreenTest](../src/test/kotlin/dev/checkout/talk/WhiteScreenTest.kt).

## The bug

The screen was built with `combine` over several flows. One of them, the least important,
never emitted. The screen had no first frame at all: not a slow frame, no frame.

## The mechanism

`combine` emits nothing until every input has produced a value. Its implementation marks each
input as uninitialised and runs the transform only when the count of absent values reaches zero.
(Read through DeepWiki, which quotes `combineInternal` in kotlinx.coroutines; measured by the
talk's tests.)

Two details matter for the fix:

- If one input completes without ever emitting while others are still open, the combined flow
  neither emits nor completes. (Read through DeepWiki only.)
- `null` is a value. An input that emits `null` counts as having emitted. (Measured:
  [probe](evidence/coroutines-test-background-scope-probe.txt), line B.)

So the cause is not a slow input. It is an input for which `combine` has no value at all.

## Strategies compared

| Strategy | First frame | A silent mandatory input | Later changes to mandatory inputs | Cost | Status |
|---|---|---|---|---|---|
| **Tiered loading**: await the mandatory tier with `first()`, render, then add the rest | As soon as the mandatory tier has arrived | No state is produced. Reasoned from the code: `first()` would wait. No test shows it. | Not seen: the tier is read once | A second, imperative mechanism next to the flows; ordering code | The talk's fix. Kept, untouched. |
| **Every input starts as "not yet"**, one `combine` derives the frame | At once: `Loading` while a mandatory input is missing, or `Content` straight away if they have all arrived by the first combination | `Loading`, a state the screen can draw | Reach the screen through the same flow | One operator per input and one `if` | Ours. Added next to the tiered test. |
| Initial value only for the optional inputs | When the mandatory inputs have arrived | Silence again | Reach the screen | Less code | Not built. It is the same idea, applied halfway. |
| `stateIn(scope, Eagerly, null)` per input | The same as "not yet" | The same | The same | Needs a scope, starts collecting at once, conflates | Not chosen: more machinery than `onStart` for an adapter inside one `combine` |
| A wrapper type in place of `null` | The same | The same | The same | A small sealed type | Needed only when an input can legitimately emit `null` |
| A timeout on the slow input | After the timeout | Depends on what the timeout emits | Reach the screen | A delay the user feels | Not a fix: the input was stuck, not slow |

The first two are both in the repository. They differ in where the rule "what must have
arrived" lives: in the order of imperative steps, or in one condition.

## The fix in code

```kotlin
private fun frames(/* five input flows */): Flow<Frame> = combine(
    products.notYet(), user.notYet(), currency.notYet(), deliveryPrices.notYet(), promos.notYet(),
) { products, user, currency, deliveryPrices, promos ->
    if (products == null || user == null || currency == null) Frame.Loading
    else Frame.Content(products, user, currency, deliveryPrices, promos)
}.distinctUntilChanged()

/** The flow's own values, after an initial null that stands for "not yet". */
private fun <T : Any> Flow<T>.notYet(): Flow<T?> = onStart<T?> { emit(null) }
```

What it does not do:

- It does not decide which inputs are mandatory. That stays a product decision; it is one line now.
- It does not suit an input that can legitimately emit `null`.
- It does not recover what a hot input without replay emitted before `combine` subscribed.
  Any collector of such a flow has that gap. A `StateFlow`, a Room query flow or a cold flow
  does not.

## A test that could not fail

While writing the tests above, three correct tests failed with an empty result. The cause was
in the harness, and the same pattern was in one of the talk's tests.

`combine renders nothing while one input stays silent` launched its collector in
`backgroundScope` and then called `advanceUntilIdle()`. That call returns as soon as no
foreground work is left. A collector that lives only in `backgroundScope` is background work,
so it never ran, and `rendered` stayed null whatever the inputs were.

Checked by mutation: replace the silent `emptyFlow<String>()` with `flowOf("promo")`, so that
every input emits.

| | With `advanceUntilIdle()` | With `runCurrent()` |
|---|---|---|
| The test as written | passes | passes |
| Mutated, every input emits | passes, and should not | fails |

The fix is one line: `runCurrent()`, which runs background work too. What the test says about
`combine` was always true. Now the test shows it.

## Tests

| Test | From | Why it exists | How it proves it |
|---|---|---|---|
| `combine renders nothing while one input stays silent` | talk, harness fixed by us | The bug | Collects, runs the scheduler, nothing rendered. Fails if the silent input is made to emit. |
| `combine renders only once the last input finally emits` | talk | The screen waits on its least important input | A foreground collector; null before the emission, a screen after |
| `tiered loading renders the first frame without the optional tier` | talk | The talk's fix | The first state is the mandatory tier alone |
| `inputs that start as not-yet render the first frame without the optional one` | ours | Our fix | The last frame is `Content` with `promos = null` |
| `a silent mandatory input renders loading instead of nothing` | ours | A state where there was none | The frames are exactly `[Loading]` |
| `a mandatory input that changes later updates the frame` | ours | The inputs stay live | `Loading`, then `RUB`, then `EUR`, each asserted |

In our three tests the silent inputs are `MutableSharedFlow()`: open, subscribed, and never
emitting. An `emptyFlow()` completes at once, which is a different situation.

## Gotchas

- `advanceUntilIdle()` does not run work that lives only in `backgroundScope`. `runCurrent()` does.
- A test whose passing condition is "nothing happened" passes when nothing ran. Mutate it once.
- `emptyFlow()` is not a stuck input. It is a finished one.
- `null` is a value for `combine`. The first diagnosis of the three failing tests blamed
  `combine`'s handling of `null`. A probe with no nulls in it failed the same way, which
  cleared `combine` and pointed at the harness.
- The exact sequence of intermediate frames depends on how `combine` batches. `Loading` is
  not guaranteed to come first when the mandatory inputs are fast. Assert the last frame, or
  a list that `distinctUntilChanged` makes unambiguous.
- A `runBlocking` or `runTest` test body must end in a statement of type `Unit`, or JUnit 4
  rejects the method.

## Evidence

The mutated test before the fix ([log](evidence/white-screen-mutation-before-fix.log)):
`BUILD SUCCESSFUL`. After the fix ([messages](evidence/white-screen-mutation-after-fix-messages.txt),
[console log](evidence/white-screen-mutation-after-fix.log)):

```
FAIL combine renders nothing while one input stays silent | java.lang.AssertionError: actual value is not null expected null, but was:<Screen(products=products, user=user, currency=RUB, promos=promo)>
```

Our three tests under the wrong harness ([messages](evidence/white-screen-new-tests-wrong-harness-messages.txt),
[console log](evidence/white-screen-new-tests-wrong-harness.log)):

```
FAIL inputs that start as not-yet render the first frame without the optional one | java.util.NoSuchElementException: List is empty.
FAIL a mandatory input that changes later updates the frame | java.util.NoSuchElementException: List is empty.
FAIL a silent mandatory input renders loading instead of nothing | java.lang.AssertionError: expected:<[Loading]> but was:<[]>
```

The probe that told the two explanations apart: [text](evidence/coroutines-test-background-scope-probe.txt).
The final class: [6 tests, 0 failures](evidence/white-screen-final.xml), the same in three runs.

## Left alone, and raised with the author

- `loadAdditional()` is called again inside `optional.onEach` in the tiered test, so every
  optional emission would reload the secondary tier. It may be intended. We did not change it.
- The class comment says "Bug 4"; the README has three stories.

## Not verified

- What the tiered example does with a silent mandatory input.
- A device: these are plain JVM tests and a device adds nothing.
