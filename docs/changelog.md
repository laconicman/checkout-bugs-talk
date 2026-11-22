# Changelog against upstream

Base: upstream `main` at `a6d80b5` (2026-11-02). Each change below keeps the hash of its
branch: they reached this fork's `main` through merge commits, never squashed.

## The changes

| # | Branch | Commit | Kind | What changed | Unit tests after |
|---|---|---|---|---|---|
| [1](https://github.com/laconicman/checkout-bugs-talk/pull/1) | `fix/ci-android-sdk` | `0f303cb` | minor, unblocks all | CI: pass `packages: ''` to `setup-android`, two lines | 28 |
| [2](https://github.com/laconicman/checkout-bugs-talk/pull/2) | `fix/orphan-cleaner-fixpoint` | `45b10d8` | fix | The orphan sweep repeats until clean and stops if a pass removes nothing | 31 |
| [3](https://github.com/laconicman/checkout-bugs-talk/pull/3) | `test/real-room-migration` | `2b03b1d` | pivotal finding | A real Room upgrade test; the table-rebuild test; four comments reworded | 34 |
| [4](https://github.com/laconicman/checkout-bugs-talk/pull/4) | `feat/sweep-after-migration` | `b48fe18` | pivotal fix | `Migration.thenClearRowsWithoutParent()` | 36 |
| [5](https://github.com/laconicman/checkout-bugs-talk/pull/5) | `feat/frozen-app-decomposed-save` | `0ba3572`, `27fec90` | pivotal finding and fix | Topology tests; the marker's blind spot; a transaction that cannot suspend; `insertNow` | 44 |
| [6](https://github.com/laconicman/checkout-bugs-talk/pull/6) | `test/white-screen-collects` | `4a5209c` | minor, a real defect | One line so that an existing test can fail | 44 |
| [7](https://github.com/laconicman/checkout-bugs-talk/pull/7) | `feat/white-screen-initial-values` | `4e56051` | pivotal fix | Inputs that start as "not yet", one `combine` | 47 |
| [8](https://github.com/laconicman/checkout-bugs-talk/pull/8) | `ci/emulator-device-tests` | `e588d5c` | optional | Emulator job and instrumented copies of two classes | 47, and 9 on device |
| [10](https://github.com/laconicman/checkout-bugs-talk/pull/10) | `fix/sweep-shadowed-rowid` | `9a3a612` | fix | The sweep addresses rows by a rowid name the table has left free | 48 |

The first eight reached `main` in one merge commit, `7d901b1`. The ninth change was found
while these pages were reviewed, and merged on its own as `a754010`.

Fork only, not meant for upstream: `REVIEW.md` (`71b156d`), this `docs/` folder
([#9](https://github.com/laconicman/checkout-bugs-talk/pull/9)), and the merge commits.

## Pivotal changes

- **Story 2.** The cause was restated: enforcement is not on yet during a migration, and
  SQLite's own procedure ends with a check that hand-written migrations skip. The fix runs
  that check as a repeating sweep at the end of every migration. See [Story 2](story-2-phantom-items.md).
- **Story 1.** Who waits depends on the kind of database, which the in-memory tests hid. The
  marker does not see the shape that froze the app. A transaction whose body cannot suspend
  rules that shape out at compile time. See [Story 1](story-1-frozen-app.md).
- **Story 3.** One `combine` over inputs that each start as "not yet" replaces the second,
  imperative mechanism, and keeps the inputs live. See [Story 3](story-3-white-screen.md).

## Minor changes, all of them

| Change | Where | Why |
|---|---|---|
| CI reaches Gradle | `.github/workflows/tests.yml` | The default packages of `setup-android@v3` include one that no longer exists |
| Test and class counts kept true in every step | `README.md` | The README states them; they went 28 → 31 → 34 → 36 → 44 → 47 → 48, eight → ten classes |
| "For story 1, continue with" list | `README.md` | Story 2 had such a list; story 1 now has a second class |
| `RealRoomMigrationTest` added to the story 2 list | `README.md` | |
| Story table rows 1 and 3 extended | `README.md` | Each names what was added |
| "Scope of the examples" corrected | `README.md` | It said no test runs a complete Room upgrade; one does now |
| "On a device (optional)" section | `README.md` | How to run the instrumented copies |
| "Room disables the pragma" reworded | `Migrations.kt`, `OrderTables.kt`, `PhantomOrderItemsTest.kt`, one trailing comment in `PhantomRidsTest.kt` | It does not; it has not enabled it yet. Later narrowed to "by default". |
| "Every other database call queues behind it" qualified | class comment of `FrozenAppTest` | True in memory; under WAL plain reads get through |
| `advanceUntilIdle()` → `runCurrent()` in one existing test | `WhiteScreenTest.kt` | The collector never ran |
| `insertNow`, a non-suspending insert | `OrderRowDao` | The transaction body that cannot suspend needs it |
| `kspTest`, `kspAndroidTest` | `build.gradle.kts` | Test-only `TalkDatabaseV43` |
| `testInstrumentationRunner`, three `androidTestImplementation` lines | `build.gradle.kts` | The instrumented copies; one new artifact, `androidx.test.ext:junit:1.3.0` |
| `device` job, limited to manual runs and `main` | `.github/workflows/tests.yml` | |

Noticed and deliberately not changed:

- `loadAdditional()` is called again inside `optional.onEach` in the tiered test.
- The class comment of `WhiteScreenTest` says "Bug 4"; the README has three stories.
- `actions/setup-java@v4` is flagged as deprecated in favour of v5, and several actions still
  target Node 20.
- The workflow downloads Robolectric's runtime on every run without a cache.
- The private test helpers `asMigration` and `countOf` are duplicated across files. Each file
  is meant to read alone next to its slide.

## What we got wrong on the way

Kept on purpose. Each of these was a confident statement at the time.

| What we first thought | What was true | How we found out |
|---|---|---|
| The in-memory deadlock comes from Room's single-connection pool | That pool belongs to Room's driver mode, which this repository does not use. The single connection is the framework's. | Reading room-runtime 2.8.4 itself |
| We were reading Room 2.8.4 | Several paths had been read from an older commit of the androidx repository | Downloading the published source jars |
| Room disables foreign keys for migrations (the talk's wording, repeated by us) | It has not enabled them yet | The generated `onOpen`; then a test |
| The sweep needs a guard for `WITHOUT ROWID` tables, or it will loop | SQLite rejects the delete there; nothing loops | The planned test failed for an unpredicted reason before the fix |
| No other input can stall the loop, so a general "no progress" check is unnecessary | A column named `rowid` stalls it. The suggestion we had dismissed was right. | Devin Review; then the `sqlite3` shell; then a 120 s hang |
| `@RestrictsSuspension` can allow suspending DAO calls and forbid the rest | Members of a restricted type are restricted too | DeepWiki on the Kotlin compiler, fast mode; not compiled by us |
| A shadowed `rowid` only makes the delete miss | It can also remove a valid row. Fixed in #10: the sweep uses a rowid name the table has left free. | Devin Review on the docs; then the `sqlite3` shell |
| JUnit's `@Test(timeout)` will bound a hanging test | Not under `RobolectricTestRunner` | Removing the guard and watching it hang |
| "Did not finish in 2 s" shows a call was waiting | Only if the call had started | Devin Review |
| `reader.isCompleted` shows the read succeeded | It is true for failures too, and depended on timing | Devin Review |
| A transaction that cannot suspend "cannot wait" | Blocking calls still compile | Devin Review |
| The new white-screen tests fail because `combine` treats `null` as absent | The collector never ran; `null` is a value | A probe that changed one variable |
| `emptyFlow()` stands for a stuck input | It completes at once | Devin Review |
| The instrumented copies can differ from the originals in the runner only | Their test names must change too: DEX rejects spaces | D8 |
| The emulator job can run on every push | It was described as optional and cost six minutes each time | Devin Review |
| A commit hash in a review reply stays valid | Not after a rebase | Our own re-read; replies edited in place |

Of these sixteen, seven were caught by the reviewer, five by our own tests, probes, builds and re-reading,
three by reading sources, and one by DeepWiki.

## Review rounds

Each pull request was reviewed by Devin Review and audited until nothing was owed.

| PR | Findings | Taken | Documented as a limit | Declined with a reason |
|---|---|---|---|---|
| #1 | 0 | | | |
| #2 | 3 | 2: the `rowid` stall, the unbounded test | | 1: the sweep outliving a failed test |
| #3 | 2 | 1 and a half: "by default", `tearDown` | | half: the seed's own handle |
| #4 | 1 | | 1: multi-step upgrades | |
| #5 | 3 | 3: started signal, awaited read, wording | | |
| #6 | 0 | | | |
| #7 | 3 | 2: open silent inputs, the `RUB` assertion | 1: hot inputs without replay | |
| #8 | 2 | 1: the job condition | | 1: one statement between signal and call |
| #9, these pages | 3 | 3: wording about `Loading` first and about "every migration"; the shadowed `rowid`, fixed in #10 | | |
| #10 | 1 | | 1: a generated column named `rowid` | |
| Total | 18 | 12 and a half | 3 | 2 and a half |

## Not done

- Upstream has the CI repair as an open pull request. Nothing else has been proposed yet.
- No cache for the Robolectric runtime.
- No handling of a generated column named `rowid`.
- No test for the sweep on a multi-step upgrade.
- No run on a physical device or on another API level.
- Nothing with a `SQLiteDriver`.
