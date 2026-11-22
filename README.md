# When Checkout Checks Out

Runnable companion examples for the conference talk **When Checkout Checks Out**:
three ways a checkout can fail through interactions between Room, SQLite and Kotlin
coroutines.

These are small educational reproductions. Tests assert the surprising behavior
as well as countermeasures, so a successful run includes reproducing the bugs.
There is no application UI, backend, account or emulator to set up.
The suite contains 47 tests across ten classes.

## Run

Requirements: JDK 17 or newer, Android SDK platform 36 and Build Tools 36.0.0.
Android Studio can install the SDK components. Set `ANDROID_HOME` to your SDK directory
or put `sdk.dir=/absolute/path/to/Android/sdk` in an untracked `local.properties`.
Use the same JDK for Android Studio and the terminal.

```sh
./gradlew testDebugUnitTest --quiet
```

On Windows, use `gradlew.bat`. The wrapper downloads Gradle; dependencies come from
Google Maven and Maven Central. The first Robolectric run also downloads its Android
runtime. No private repositories or parent checkout are required.

Open this folder in Android Studio as a Gradle project to browse and debug the tests.

## Follow the talk

| Story | Start here | What to watch for |
| --- | --- | --- |
| 1. The frozen app | [FrozenAppTest](src/test/kotlin/dev/checkout/talk/FrozenAppTest.kt) | A transaction waits for a database-backed flow in another scope; a slow call holds the transaction; a coroutine marker rejects network access. The marker's blind spot, and a transaction body that cannot suspend. |
| 2. Phantom items | [OrderTablesCascadeTest](src/test/kotlin/dev/checkout/talk/OrderTablesCascadeTest.kt) | Cascades with foreign keys enabled versus disabled, then identifier reuse after dropping a table. |
| 3. The white screen | [WhiteScreenTest](src/test/kotlin/dev/checkout/talk/WhiteScreenTest.kt) | `combine` waits for every source; staged loading can render before optional data arrives. Inputs that start as "not yet" keep a single `combine` and render at once. |

For story 1, continue with:

- [FrozenAppTopologyTest](src/test/kotlin/dev/checkout/talk/FrozenAppTopologyTest.kt): which calls wait for an open transaction on an in-memory database, a rollback-journal file and a write-ahead-log file.

For story 2, continue with:

- [PhantomOrderItemsTest](src/test/kotlin/dev/checkout/talk/PhantomOrderItemsTest.kt): the minimal parent/child example and explicit descendant cleanup.
- [PhantomRidsTest](src/test/kotlin/dev/checkout/talk/PhantomRidsTest.kt): the three-level order → product → identifier schema.
- [WouldDeleteHaveHelpedTest](src/test/kotlin/dev/checkout/talk/WouldDeleteHaveHelpedTest.kt): why `DELETE` and `DROP TABLE` differ, and why leaving orphans is still unsafe.
- [PragmaInsideTransactionTest](src/test/kotlin/dev/checkout/talk/PragmaInsideTransactionTest.kt): changing `foreign_keys` inside a transaction has no effect.
- [OrphanCleanerTest](src/test/kotlin/dev/checkout/talk/OrphanCleanerTest.kt): finding and removing broken references with `foreign_key_check`.
- [RealRoomMigrationTest](src/test/kotlin/dev/checkout/talk/RealRoomMigrationTest.kt): the same orphans on a genuine Room upgrade of a file database, and where `foreign_keys` stands during and after it.

Run one story:

```sh
./gradlew testDebugUnitTest --tests 'dev.checkout.talk.FrozenAppTest' --quiet
```

The HTML report is `build/reports/tests/testDebugUnitTest/index.html`.
Use `--rerun-tasks` for a fresh run before presenting.

## On a device (optional)

`RealRoomMigrationTest` and `FrozenAppTopologyTest` have instrumented copies in
`src/androidTest/kotlin/dev/checkout/talk`. With a device or an emulator connected:

```sh
./gradlew connectedDebugAndroidTest
```

The `device` job of the workflow runs them on an API 35 emulator. It runs when the
workflow is started by hand and on pushes to `main`, not on every push.

## Scope of the examples

Most migration examples explicitly toggle SQLite foreign-key enforcement to model
the migration environment; only `RealRoomMigrationTest` runs a complete Room
version upgrade.
The deadlock test bounds its wait with a timeout. The network call is simulated.
The cleanup helpers target these small demo schemas; they are not general-purpose
migration utilities (for example, the descendant walker assumes no reference cycles).

Source lives in `src/main/kotlin/dev/checkout/talk`; tests and their in-memory SQLite
fixture live in `src/test/kotlin/dev/checkout/talk`.
