# Gotchas

Every trap we met, by layer. "Shown by" names the test or the evidence file; "read" means it
comes from source or documentation and nothing here demonstrates it.

## SQLite

| Gotcha | Consequence | Shown by |
|---|---|---|
| `PRAGMA foreign_keys` is off on every new connection | A correct `ON DELETE CASCADE` does nothing until somebody turns it on | `RealRoomMigrationTest` |
| The pragma is a no-op inside a transaction, with no error | A migration cannot turn enforcement on for itself | The talk's `PragmaInsideTransactionTest` |
| With enforcement on, `DROP TABLE` deletes the rows first and that fires cascades | A table rebuild silently deletes child rows | `with foreign keys on a table rebuild fires the cascade and empties the child` |
| `PRAGMA foreign_key_check` reports only rows whose immediate parent is missing | One pass leaves grandchildren behind | `clears the whole chain under an orphan`; [E5](evidence/sqlite-experiments.out) |
| `foreign_key_check` works with enforcement off and inside a transaction | It can be the last statement of a migration | [E1](evidence/sqlite-experiments.out) |
| A missing parent table is not an error for the check: every child row is reported | The sweep also cleans up after a dropped parent | [E2](evidence/sqlite-experiments.out) |
| For a `WITHOUT ROWID` table the check reports a NULL rowid | `DELETE ... WHERE rowid` fails with `no such column: rowid` | [E3](evidence/sqlite-experiments.out); [messages](evidence/orphan-sweep-before-fix-messages.txt) |
| A column named `rowid` hides the real rowid | A delete by rowid matches nothing; a loop around it never ends | `stops instead of looping when a pass removes nothing` |
| `DROP TABLE` removes the table's `sqlite_sequence` row; `DELETE` keeps it | Ids restart after a drop, so old orphans attach to new rows | The talk's `PhantomRidsTest`; [E6](evidence/sqlite-experiments.out) |
| `PRAGMA defer_foreign_keys` does nothing while enforcement is off | It is not a way around the migration problem | [E7](evidence/sqlite-experiments.out) |

## Android framework

| Gotcha | Consequence | Shown by |
|---|---|---|
| `SQLiteOpenHelper` runs `onUpgrade` inside a transaction and `onOpen` after it | Anything enabled in `onOpen` is not in force during a migration | `RealRoomMigrationTest`; read |
| The connection pool has one connection unless the journal mode is WAL | Outside WAL, a read from another thread waits for an open transaction | `FrozenAppTopologyTest` |
| A transaction holds the primary connection until it ends | Writes from other threads always wait | `FrozenAppTopologyTest` |
| The wait is in Java, with no timeout | No `SQLITE_BUSY`, no exception: a hang | read |
| Enforcement can be enabled in `onConfigure`, before the upgrade transaction | "Off during migrations" is a default, not a law. Enabling it brings the `DROP TABLE` cascade. | read; raised in review |

## Room 2.8.4

| Gotcha | Consequence | Shown by |
|---|---|---|
| Room does not turn foreign keys off for a migration; it turns them on in its generated `onOpen` | There is nothing to turn back on from inside `migrate()` | `RealRoomMigrationTest` |
| Generated auto-migrations run `foreign_key_check`; hand-written ones get nothing | The check is the author's job | read |
| `MigrationTestHelper` neither enables foreign keys nor checks for violations | A migration test would not have caught the phantom items | read |
| `withTransaction` occupies a serial executor, and suspending DAO writes go to the same one | Writes from other coroutines queue inside Room, in every journal mode | `FrozenAppTopologyTest` |
| With no driver set, Room's pool does no connection management | Read concurrency is decided by the framework, not by Room | read; `FrozenAppTopologyTest` |
| The default journal mode is `AUTOMATIC`: WAL unless the device is low on RAM | An in-memory test database behaves unlike most production databases | read |
| `TransactionElement` is internal; `withTransaction` has no overload taking a context | An app cannot ask Room whether a transaction is open; it needs its own marker | read |
| `runInTransaction` takes a plain block | A suspending call inside it is a compile error | [log](evidence/suspend-call-in-runInTransaction-compile-error.log) |
| `runInTransaction` and non-suspending DAO functions refuse the main thread | Wrap them in `withContext(Dispatchers.IO)` | `gathering before a transaction that cannot suspend` |
| Room rewrites its identity hash after a successful migration | A file made by one database class opens under another with the same tables | `RealRoomMigrationTest` |
| A wrapper that overrides only `migrate(SupportSQLiteDatabase)` fails once a `SQLiteDriver` is set | Room then calls `migrate(SQLiteConnection)` | read |
| An extra `@Database` class in test sources needs `kspTest`; in instrumented sources, `kspAndroidTest` | Without it there is no generated implementation | the build |

## Coroutines and coroutines-test 1.10.2

| Gotcha | Consequence | Shown by |
|---|---|---|
| `combine` emits nothing until every input has emitted | One silent input, no first frame | The talk's `WhiteScreenTest` |
| `null` counts as a value for `combine` | An initial `null` is enough to unblock it | [probe](evidence/coroutines-test-background-scope-probe.txt) |
| `advanceUntilIdle()` returns once no foreground work is left | A collector launched only in `backgroundScope` never runs; "nothing rendered" passes vacuously | [probe](evidence/coroutines-test-background-scope-probe.txt); [mutation](evidence/white-screen-mutation-before-fix.log) |
| `runCurrent()` runs background work too | It is the call to use with `backgroundScope` | [mutation, after](evidence/white-screen-mutation-after-fix-messages.txt) |
| `emptyFlow()` completes at once | It models a finished input, not a stuck one; use an open `MutableSharedFlow()` | raised in review |
| A context element reaches only the coroutines that inherit that context | A marker set around a transaction is absent in another scope's coroutine | `the marker does not see a request made by the awaited flow` |
| `Job.isCompleted` is true for failed and cancelled jobs | It is not evidence of success; await the job | raised in review |
| `withTimeoutOrNull` cannot stop a blocking call | The call keeps running after the test moved on | [log](evidence/orphan-sweep-bounded-in-test-body.log) |
| A `runBlocking` or `runTest` test body must end in `Unit` | JUnit 4 rejects a test method that returns a value | the build |

## Robolectric 4.17-beta-2

| Gotcha | Consequence | Shown by |
|---|---|---|
| JUnit's `@Test(timeout = …)` does not bound a busy loop under `RobolectricTestRunner` | A hang stays a hang; bound it in the test body | [log](evidence/orphan-sweep-junit-timeout-does-not-bound.log) |
| The Android runtime, about 200 MB, is downloaded from Maven Central when tests first run | A network reset fails whole test classes as `classMethod`; a re-run passes | [excerpt](evidence/robolectric-runtime-download-flake.txt) |
| `@Config(sdk = [35])` picks that runtime and has nothing to do with `compileSdk` | No SDK platform 35 needs to be installed | read |
| Only the lowest SQLite classes are replaced; pool and open helper are the framework's | Concurrency results carry over to a device, as the emulator run confirmed | emulator run; read |

## Build, DEX and the instrumented copies

| Gotcha | Consequence | Shown by |
|---|---|---|
| Backtick test names put spaces into class names, and DEX before version 040 rejects them | With `minSdk` 23 the instrumented APK does not build | [D8 error](evidence/device-apk-d8-space-in-name.txt) |
| A source directory shared by the unit and the instrumented source sets confuses Android Studio | We keep instrumented copies; they must be kept in step by hand | design decision |
| The instrumented tests see the same `internal` declarations as the unit tests | No visibility change was needed | the build |

## CI and GitHub

| Gotcha | Consequence | Shown by |
|---|---|---|
| `android-actions/setup-android@v3` installs `tools platform-tools` by default, and `tools` no longer exists | The job dies before Gradle | [excerpt](evidence/upstream-ci-failure.txt) |
| A re-run keeps the failed attempt's artifact next to the new one, under the same name | Reading "the" artifact can read the failure | our own miscount, caught |
| An emulator job without a condition runs on every push and pull request | About six minutes each time; ours runs on manual dispatch and on `main` | raised in review |
| A workflow started by hand needs `workflow_dispatch`, which upstream already has | `gh workflow run tests.yml --ref <branch>` works | run 45013189575 |
| `sdkmanager` now prints that it is deprecated in favour of `android sdk` | The workflow still works; it will need attention one day | console output |
| Review comments that quote a commit hash go stale after a rebase | We edited the replies in place | process |

## Tooling on the way

| Gotcha | Consequence |
|---|---|
| The Homebrew cask `temurin@17` is a `.pkg` and needs sudo | The `openjdk@17` formula does not; `JAVA_HOME` is then set per shell |
| DeepWiki's index of `androidx/androidx` covers `room3`, not the Room 2.8.4 pinned here | Room 2.8.4 was read from the published source jars instead |
| DeepWiki sees only the default branch | Until the stack was merged it could review plans, not diffs |
| A plausible mechanism plus confirming samples is not evidence | Twice a first explanation was wrong; a probe that changed one variable settled it |
