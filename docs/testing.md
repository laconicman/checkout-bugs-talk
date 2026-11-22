# Testing: why and how

The talk's repository has an unusual rule, and we kept it: a test asserts the bug as well as
the fix. A green run therefore includes reproduced bugs. That makes one question matter more
than usual: could this test fail?

## The suite

| Class | Upstream | Added here | Now | Runs on |
|---|---|---|---|---|
| `FrozenAppTest` | 3 | 2 | 5 | Robolectric |
| `FrozenAppTopologyTest` | | 6 | 6 | Robolectric, emulator |
| `OrderTablesCascadeTest` | 4 | | 4 | Robolectric |
| `OrphanCleanerTest` | 2 | 4 | 6 | Robolectric |
| `PhantomOrderItemsTest` | 5 | | 5 | Robolectric |
| `PhantomRidsTest` | 4 | 1 | 5 | Robolectric |
| `PragmaInsideTransactionTest` | 3 | 1 | 4 | Robolectric |
| `RealRoomMigrationTest` | | 3 | 3 | Robolectric, emulator |
| `WhiteScreenTest` | 3 | 3 | 6 | plain JVM |
| `WouldDeleteHaveHelpedTest` | 4 | | 4 | Robolectric |
| **Total** | **28** | **20** | **48** | 9 also on the emulator |

```sh
./gradlew testDebugUnitTest                # the 48 unit tests
./gradlew connectedDebugAndroidTest        # the 9 device tests; needs a device or an emulator
```

## Three environments

| Environment | What runs | What it can show | What it cannot |
|---|---|---|---|
| Host, macOS arm64, OpenJDK 17.0.21, Robolectric 4.17-beta-2 at SDK 35 | All 48 | Logic, Room, the framework's Java, SQLite semantics | The device's SQLite build and resources |
| CI, Linux x86_64, same Robolectric | All 48 | That the result does not depend on the developer's machine | The same |
| CI, emulator, API 35, x86_64 | 9 instrumented copies | The framework-dependent results on Android 15 itself | Other API levels, real hardware |

Robolectric's fidelity is better than it sounds: in its native SQLite mode only
`SQLiteConnection` and `CursorWindow` are replaced, so the connection pool, sessions and
`SQLiteOpenHelper` are the real framework classes. (Read through DeepWiki, from an index
720 commits behind; the emulator run is what confirms it for the cases we care about.)

The `sqlite3` shell is a fourth, narrow environment for engine questions. It is version
3.54.0 on the host, not Android's build, so it settles what SQLite does and not what a
device ships.

## Rules we held to, each one earned

### 1. A test that claims a defect is run against the defect first

`clears the whole chain under an orphan` and `clears a chain inside a self-referencing table`
were written before the fix and run against upstream's code. Both failed with
`expected:<1> but was:<2>`. Then the fix went in. ([Messages](evidence/orphan-sweep-before-fix-messages.txt).)

That same run paid for itself a second way. A third test, for a guard we had planned, failed
for a reason we had not predicted: SQLite already rejected the statement. The guard was
unnecessary and we removed it before it shipped.

### 2. A test that asserts "nothing happened" gets mutated once

`combine renders nothing while one input stays silent` passed. It also passed when every
input emitted, because its collector never ran. One mutation showed that; one line fixed it.
See [Story 3](story-3-white-screen.md#a-test-that-could-not-fail).

When a passing condition is an absence (null, an empty list, no exception), every way of not
running produces that absence too.

### 3. One variable at a time

Three new tests came back empty. The first explanation was that `combine` treats `null` as
"no value yet". It fitted every observation, and every observation had also used the same
harness. A probe with no nulls in it failed the same way; a probe with nulls and a different
harness passed. ([Probe](evidence/coroutines-test-background-scope-probe.txt).)

The topology tests carry their own control for the same reason. Five cases are "did not
finish in 2 s". The sixth, the WAL read, goes through the same helper and finishes in tens of
milliseconds. A slow scheduler would show up there first.

### 4. A wait is measured from the moment the work has started

The first topology helper started a coroutine and gave it 2 s. "Not finished" could then mean
"not started". The helper now waits for a signal from inside the coroutine and only then
starts the clock. One statement still separates that signal from the DAO call; nothing in a
test can observe the call from inside Room.

### 5. Every path that can hang is bounded, and the bound is checked

| What can hang | How it is bounded |
|---|---|
| The deadlock itself | `withTimeoutOrNull(3.seconds)`, the talk's own pattern |
| Each waiting case in the topology tests | `withTimeoutOrNull(2.seconds)` inside the transaction, then `withTimeout(5.seconds)` for the release |
| The sweep, if its guard ever regresses | The call runs on another thread and the test waits with `withTimeoutOrNull(5.seconds)` |
| The emulator job | `timeout-minutes: 30` |

The third row took two attempts. JUnit's `@Test(timeout = 5_000)` did not bound the loop
under `RobolectricTestRunner`: with the guard removed, the run still hung and had to be
killed ([log](evidence/orphan-sweep-junit-timeout-does-not-bound.log)). With the bound in the
test body, the same broken code fails in 5 s and the rest of the class passes
([log](evidence/orphan-sweep-bounded-in-test-body.log)).

A bound is itself a claim. We tested it by breaking the code it protects.

### 6. Timing and scheduling tests are run three times

The topology class and the white-screen class were each run three times locally before the
result was written down. All runs agreed.
([Topology 1](evidence/topology-host-run1.xml), [2](evidence/topology-host-run2.xml), [3](evidence/topology-host-run3.xml).)

### 7. Predictions are written before the run

Each new test was specified with its expected values first. When a run disagreed, the
disagreement was reported and investigated; the assertion was never adjusted to fit. That is
how the unnecessary guard, the harness problem and the D8 limit were found.

### 8. A compile-time claim is checked by compiling

"A suspending call inside `runInTransaction` does not compile" was verified by adding one,
reading the compiler's error, and removing it
([log](evidence/suspend-call-in-runInTransaction-compile-error.log)). There is no test for it;
a unit test cannot assert a compile error without extra tooling.

### 9. Counts come from the result files

Every figure in the pull requests was tallied from the JUnit XML in the uploaded artifact,
not from the console. A re-run leaves two artifacts with the same name; once, the first one
we read was the failed attempt's. Read the newest.

### 10. A green run is not always a run

Upstream's CI had never executed a test. On the fork, four CI runs and the very first
local run failed while Robolectric downloaded its 200 MB runtime, and each passed on re-run
with nothing changed
([excerpt](evidence/robolectric-runtime-download-flake.txt)). Look at what failed before
concluding anything from a red or a green mark.

## What is not tested

- A physical device, and API levels other than 35.
- Room with a `SQLiteDriver` set.
- The sweep on a multi-step upgrade path.
- The tiered example with a silent mandatory input.
- A deadlock on a WAL file caused by a producer that writes.
