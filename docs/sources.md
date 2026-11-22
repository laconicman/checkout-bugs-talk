# Sources

What was read, asked and measured, and how far each can be trusted. The story pages mark
each statement as measured, read or reasoned; this page says where "read" comes from.

## Order of trust

1. **A test in this repository**, on the host, on CI and, for nine tests, on an emulator.
2. **A command on the `sqlite3` shell** ([script](evidence/sqlite-experiments.sh),
   [output](evidence/sqlite-experiments.out)). It is SQLite 3.54.0 on macOS, not Android's build.
3. **Source code of the pinned versions**, read from the published source jars.
4. **Official documentation**, quoted with its heading.
5. **DeepWiki**, an AI-generated reading of a repository's default branch. Useful for finding
   the right file and for a second opinion; every load-bearing answer was then checked by 1 to 4.

## Source jars read

All from Google's Maven repository, for the versions in `build.gradle.kts`.

| Artifact | Version | What was read there |
|---|---|---|
| `androidx.room:room-runtime-android` | 2.8.4 | `withTransaction` and `TransactionElement`; `runInTransaction`; `JournalMode.AUTOMATIC` and how it resolves; `TransactionExecutor`; how suspending DAO calls pick a dispatcher; `PassthroughConnectionPool`; `Migration` and its two `migrate` overloads |
| `androidx.room:room-runtime` | 2.8.4 | `BaseRoomConnectionManager`: the migration transaction, `onOpen` after it, the identity hash rewritten after a migration |
| `androidx.room:room-compiler` | 2.8.4 | `OpenDelegateWriter.createOnOpen`, which emits `PRAGMA foreign_keys = ON`; `AutoMigrationWriter`, which emits `foreignKeyCheck` for rebuilt tables that declare foreign keys |
| `androidx.room:room-testing-android` | 2.8.4 | `MigrationTestHelper`: its open delegates leave `onOpen` empty, and nothing in it runs `foreign_key_check` |
| `androidx.sqlite:sqlite-framework-android` | 2.6.2 | `FrameworkSQLiteOpenHelper`: the database is closed and reopened after a migration |

The Android framework's own classes (`SQLiteOpenHelper`, `SQLiteConnectionPool`,
`SQLiteSession`) are in none of these jars. For them we have DeepWiki's reading of the AOSP
mirror, and the emulator run.

## Documentation quoted

| Document | Heading | Used for |
|---|---|---|
| [SQLite Foreign Key Support](https://www.sqlite.org/foreignkeys.html#fk_enable) | «2. Enabling Foreign Key Support» | The pragma has no effect inside a transaction |
| [SQLite Foreign Key Support](https://www.sqlite.org/foreignkeys.html#fk_schemacommands) | «5. CREATE, ALTER and DROP TABLE commands» | `DROP TABLE` deletes the rows first, and that may invoke foreign key actions |
| [ALTER TABLE](https://www.sqlite.org/lang_altertable.html#making_other_kinds_of_table_schema_changes) | «8. Making Other Kinds Of Table Schema Changes» | The twelve steps: foreign keys off first (step 1), `foreign_key_check` near the end (step 10) |
| [setup-android README](https://github.com/android-actions/setup-android#additional-packages) | «Additional packages» | An empty `packages` input skips the default packages |

## DeepWiki conversations

Deep mode unless marked. "Index" is what DeepWiki reported about its own copy on 2026-11-22.

| Repository | Question, in short | Index | What we did with the answer |
|---|---|---|---|
| `aosp-mirror/platform_frameworks_base` | [Order of `onConfigure`, the upgrade transaction and `onOpen`; the foreign key default](https://deepwiki.com/search/in-androiddatabasesqlitesqlite_6899d553-7abb-4c6c-a8ee-2f1872af64eb?mode=deep) | 2025-03-27; the mirror is frozen | Confirmed by `RealRoomMigrationTest` on Robolectric and on the emulator |
| `aosp-mirror/platform_frameworks_base` | [Pool size per journal mode; who waits while a transaction is open](https://deepwiki.com/search/connection-pooling-while-one-t_7c9eb661-d6a2-4e02-be5f-33ea240511f6?mode=deep) | same | Its table is exactly what `FrozenAppTopologyTest` measured. "No timeout, no `SQLITE_BUSY`" remains read only. |
| `androidx/androidx` | [Room and foreign keys during migrations](https://deepwiki.com/search/room-and-foreign-keys-during-m_b6451486-9378-404b-8036-4aef423b400e?mode=deep) | covers `room3`, not Room 2.8.4 | Used for direction; every point re-read in the 2.8.4 jars |
| `sqlite/sqlite` | [`foreign_key_check`, `DROP TABLE`, `sqlite_sequence`, `defer_foreign_keys`](https://deepwiki.com/search/pragma-foreignkeycheck-and-rel_8c053f46-9d3c-42d2-91ab-6b2e2ae8a6a1?mode=deep) | current | Each point reproduced on the `sqlite3` shell |
| `robolectric/robolectric` | [Which SQLite classes are real framework code; WAL; defaults](https://deepwiki.com/search/fidelity-of-robolectrics-sqlit_f5194e2b-755b-4b81-9eba-adc4828939bf?mode=deep) | 2026-04-13, 720 commits behind | Agrees with what the emulator run showed for our nine tests. The rest is read only. |
| `Kotlin/kotlinx.coroutines` and this fork | [Why `combine` waits; a review of the story 3 plan](https://deepwiki.com/search/two-parts-part-1-kotlinxcorout_7c3e019c-060c-4bb3-ab64-d91926525ee9?mode=deep) | 2026-01-29, 123 commits behind | Its warning about asserting exact emission sequences shaped the tests. It did not predict the `backgroundScope` problem. |
| `JetBrains/kotlin`, fast mode | [`@RestrictsSuspension` and member functions](https://deepwiki.com/search/does-restrictssuspension-allow_c88afa4e-89fc-4d1d-8ea7-0918e466e21d?mode=fast) | 2026-07-17, 7,226 commits behind | Enough to drop the idea. Not confirmed by compiling. |
| this fork | [Review of the planned CI change](https://deepwiki.com/search/reviewer-pass-on-a-planned-ci_14b1bd9a-2115-4cdc-a020-3622ea83b336?mode=deep) | upstream's code, before the merge | No objections; confirmed nothing needs `adb` |
| this fork | [Review of the story 2 plan](https://deepwiki.com/search/propose-pass-before-i-implemen_a1b5e631-d988-4fd6-9c7d-ffc246a0cf27?mode=deep) | same | Suggested a general "no progress" check, which we dismissed and later needed. Its identity-hash worry did not hold. |
| this fork | [Review of the story 1 plan](https://deepwiki.com/search/propose-pass-before-i-implemen_3fa14f4c-2b34-4511-a061-a35e7738485e?mode=deep) | same | Led to the generous bound on the one positive case and to the reworded class comment |
| this fork | [Fact-check of thirteen statements these pages make about the talk's original code](https://deepwiki.com/search/fact-check-these-statements-ab_0f11a699-410a-43e3-a35a-4679fe0dca91?mode=deep) | same | All thirteen confirmed, with file and line |
| three repositories, fast mode | [In-memory pool size; Robolectric's native mode](https://deepwiki.com/search/for-an-in-memory-database-open_072946d5-cba0-4900-901a-2de8df7afc79?mode=fast) | | A first lead, later replaced by the deep answers above |
| three repositories, fast mode | [Three short checks](https://deepwiki.com/search/three-short-checks-1-sqlitesql_07d5b3d7-143e-4b76-89df-a3ba858f9ca0?mode=fast) | | The same |

DeepWiki reads the default branch only. While the work sat on branches it could review a
plan against upstream's code, never the diff. Each pull request said so.

## Review

Every pull request on the fork was reviewed by Devin Review and audited with `contrib`
until its verdict read `owed 0 · to re-read 0 · unexamined 0`. The fourteen findings and
what became of them are in the [changelog](changelog.md#review-rounds). The reviewer is
steered by [REVIEW.md](../REVIEW.md), which exists only on this fork. We could not confirm
whether the reviewer reads that file from the pull request's head or from the default branch.

## Versions

| Thing | Version |
|---|---|
| Room | 2.8.4 |
| androidx.sqlite | 2.6.2 |
| kotlinx.coroutines | 1.10.2 |
| Robolectric | 4.17-beta-2, SDK 35 |
| Android Gradle Plugin, KSP, Gradle | 9.2.1, 2.3.12, 9.7.0 |
| JDK | OpenJDK 17.0.21 locally, Temurin 17 on CI |
| Emulator | API 35, x86_64, `google_apis` |
| `sqlite3` shell | 3.54.0 |
