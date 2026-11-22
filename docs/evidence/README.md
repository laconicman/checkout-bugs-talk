# Evidence

Raw results behind the claims in the other pages. Everything here was produced on
2026-11-22. Paths were shortened to `<repo>`; nothing else was edited.

Console logs from Gradle show a failing test's line number but not its message. Where the
message matters, a `*-messages.txt` file next to the log holds it, taken from the JUnit XML
of the same run.

## Story 1: the frozen app

| File | What it shows |
|---|---|
| [topology-host-run1.xml](topology-host-run1.xml), [run2](topology-host-run2.xml), [run3](topology-host-run3.xml) | `FrozenAppTopologyTest` on macOS, three runs: five cases take the full 2 s, the WAL read 33 to 42 ms |
| [device-main-run-45016675327.xml](device-main-run-45016675327.xml) | The nine instrumented tests on an API 35 emulator, from `main`: the WAL read 0.1 s, the other five 2.0 s |
| [suspend-call-in-runInTransaction-compile-error.log](suspend-call-in-runInTransaction-compile-error.log) | A suspending call placed inside `runInTransaction` is rejected by the compiler |

## Story 2: phantom items

| File | What it shows |
|---|---|
| [sqlite-experiments.sh](sqlite-experiments.sh), [sqlite-experiments.out](sqlite-experiments.out) | Seven engine facts on the `sqlite3` shell, E1 to E7 |
| [sqlite-shadowed-rowid-deletes-valid-row.txt](sqlite-shadowed-rowid-deletes-valid-row.txt) | With a declared integer `rowid` column, the delete by rowid removes a valid row and leaves the orphan |
| [orphan-sweep-shadowed-rowid-before-fix-messages.txt](orphan-sweep-shadowed-rowid-before-fix-messages.txt) | The same through the sweep, before it chose its rowid name: the guard stops it |
| [sqlite-generated-rowid-column.txt](sqlite-generated-rowid-column.txt) | A generated column named `rowid` is missing from `table_info` |
| [orphan-sweep-before-fix-messages.txt](orphan-sweep-before-fix-messages.txt), [log](orphan-sweep-before-fix.log) | The new tests against upstream's single pass: two fail with `expected:<1> but was:<2>` |
| [orphan-sweep-hang-without-guard.log](orphan-sweep-hang-without-guard.log) | The repeating sweep without its guard, on a table with a `rowid` column: killed after 120 s |
| [orphan-sweep-junit-timeout-does-not-bound.log](orphan-sweep-junit-timeout-does-not-bound.log) | The same hang with `@Test(timeout = 5_000)`: still killed, after 150 s |
| [orphan-sweep-bounded-in-test-body.log](orphan-sweep-bounded-in-test-body.log) | The same broken code with the bound in the test body: fails in 5 s, the build ends in 21 s |

## Story 3: the white screen

| File | What it shows |
|---|---|
| [coroutines-test-background-scope-probe.txt](coroutines-test-background-scope-probe.txt) | `advanceUntilIdle()` skips work that lives only in `backgroundScope`; `null` is a value for `combine` |
| [white-screen-mutation-before-fix.log](white-screen-mutation-before-fix.log) | The existing test with every input emitting still passes |
| [white-screen-mutation-after-fix-messages.txt](white-screen-mutation-after-fix-messages.txt), [log](white-screen-mutation-after-fix.log) | With `runCurrent()` the same mutation fails |
| [white-screen-new-tests-wrong-harness-messages.txt](white-screen-new-tests-wrong-harness-messages.txt), [log](white-screen-new-tests-wrong-harness.log) | Our three tests coming back empty under the wrong harness |
| [white-screen-final.xml](white-screen-final.xml) | The final class: 6 tests, 0 failures |

## Build and CI

| File | What it shows |
|---|---|
| [upstream-ci-failure.txt](upstream-ci-failure.txt) | Why upstream's only CI run failed before Gradle |
| [robolectric-runtime-download-flake.txt](robolectric-runtime-download-flake.txt) | Runs failing while Robolectric downloads its runtime, locally and on CI |
| [device-apk-d8-space-in-name.txt](device-apk-d8-space-in-name.txt) | D8 rejecting a class name with spaces |
| [device-apk-assemble-after-rename.log](device-apk-assemble-after-rename.log) | The instrumented APK building once the copied tests were renamed |
| [unit-suite-main-run-45016675327.txt](unit-suite-main-run-45016675327.txt) | The unit suite on `main` after the first merge, per class: 47 tests, 0 failures. One test was added later. |

## Not kept here

The full Gradle logs, the CI artifacts as downloaded and the complete DeepWiki answers are
not in the repository. The CI runs are linked from the pull requests and expire with
GitHub's retention; the DeepWiki conversations stay reachable through the links in
[Sources](../sources.md).
