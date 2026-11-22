# Story 2: phantom items

Tests: [OrderTablesCascadeTest](../src/test/kotlin/dev/checkout/talk/OrderTablesCascadeTest.kt),
[PhantomOrderItemsTest](../src/test/kotlin/dev/checkout/talk/PhantomOrderItemsTest.kt),
[PhantomRidsTest](../src/test/kotlin/dev/checkout/talk/PhantomRidsTest.kt),
[WouldDeleteHaveHelpedTest](../src/test/kotlin/dev/checkout/talk/WouldDeleteHaveHelpedTest.kt),
[PragmaInsideTransactionTest](../src/test/kotlin/dev/checkout/talk/PragmaInsideTransactionTest.kt),
[OrphanCleanerTest](../src/test/kotlin/dev/checkout/talk/OrphanCleanerTest.kt),
[RealRoomMigrationTest](../src/test/kotlin/dev/checkout/talk/RealRoomMigrationTest.kt).
Code: [OrphanCleaner.kt](../src/main/kotlin/dev/checkout/talk/OrphanCleaner.kt),
[ChildTablesCleaner.kt](../src/main/kotlin/dev/checkout/talk/ChildTablesCleaner.kt).

## The bug

A migration cleared the order tables and forgot the grandchild table. `ON DELETE CASCADE` did not
fire inside the migration, so the grandchild rows survived. The migration had dropped and
recreated the parent tables, which restarts `AUTOINCREMENT`, so the next order reused the old
ids and inherited items nobody had ordered.

## The mechanism

The talk's comments said Room "disables" foreign keys for a migration. It does not. They are
not on yet.

| Step | What happens | Kind |
|---|---|---|
| A connection opens | Enforcement is off. SQLite's default is off, and the framework's `foreignKeyConstraintsEnabled` defaults to false. | Read: SQLite docs; framework through DeepWiki |
| `SQLiteOpenHelper` upgrades | `onConfigure`, then `beginTransaction()`, then `onUpgrade`, then commit, then `onOpen`. Room runs migrations in `onUpgrade`. | Read: framework through DeepWiki |
| Inside the migration | `PRAGMA foreign_keys` reads 0 and a transaction is open. | Measured: Robolectric and emulator |
| Changing it there | Inside a transaction the pragma is a no-op. | Measured: the talk's `PragmaInsideTransactionTest`; read: SQLite docs |
| After the commit | Room's generated `onOpen` runs `PRAGMA foreign_keys = ON`. It reads 1. | Read: room-compiler 2.8.4, `OpenDelegateWriter.createOnOpen`; measured |

SQLite says the same in its own words, in
[«2. Enabling Foreign Key Support»](https://www.sqlite.org/foreignkeys.html#fk_enable): "It is
not possible to enable or disable foreign key constraints in the middle of a multi-statement
transaction ... Attempting to do so does not return an error; it simply has no effect."

### Why it is off on purpose

SQLite's procedure for schema changes,
[«8. Making Other Kinds Of Table Schema Changes»](https://www.sqlite.org/lang_altertable.html#making_other_kinds_of_table_schema_changes),
begins with step 1: "If foreign key constraints are enabled, disable them using PRAGMA
foreign_keys=OFF."

The reason is in
[«5. CREATE, ALTER and DROP TABLE commands»](https://www.sqlite.org/foreignkeys.html#fk_schemacommands):
with enforcement on, `DROP TABLE` "performs an implicit DELETE to remove all rows from the table
before dropping it", and that DELETE "may invoke foreign key actions". A table rebuild (create
the new table, copy, drop the old one, rename) would cascade into the children. Measured in
`with foreign keys on a table rebuild fires the cascade and empties the child`: the parent row
survives the rebuild and its child is gone.

### The step everybody skips

The same procedure ends with step 10: "If foreign key constraints were originally enabled then
run PRAGMA foreign_key_check to verify that the schema change did not break any foreign key
constraints."

| Who | Runs the check? | Kind |
|---|---|---|
| Room's generated auto-migration | Yes, for tables it rebuilt that declare foreign keys (`AutoMigrationWriter` calls `DBUtil.foreignKeyCheck`) | Read: room-compiler 2.8.4 |
| A hand-written `Migration` | No | Read: room-runtime 2.8.4 |
| Room's `MigrationTestHelper` | No. Its open delegates leave `onOpen` empty and it validates table structure only. | Read: room-testing 2.8.4 |
| The Android framework | No. Nothing in the open or upgrade path runs it. | Read: framework through DeepWiki |

That is the gap the phantom items came through. The fix is to run step 10.

## Strategies compared

| Strategy | Catches | Misses | Cost | Status |
|---|---|---|---|---|
| **Walk the child tables** (`findAndClearTableAndChildren`) | Everything under a named table, through `PRAGMA foreign_key_list` | Has to be called, with the right table; clears whole tables only; recurses forever on a table that references itself (the README says it assumes no cycles) | One call per migration, remembered by its author | The talk's fix. Kept. |
| **Sweep once** with `PRAGMA foreign_key_check` (`clearRowsWithoutParent` as it was) | Rows whose immediate parent is missing, with no table list | The children of the rows it deletes: on three levels it leaves the grandchildren | One call, remembered by the author | The talk's fix. Replaced by the next row. |
| **Sweep until clean** | Whole chains of any depth, partial deletes, self-referencing tables | Tables whose rows cannot be deleted by rowid: it stops with an error | The same call | Ours. |
| **Sweep at the end of each wrapped migration** (`thenClearRowsWithoutParent`) | The same, with no author having to remember, provided the wrapper is applied where migrations are registered | A migration registered without the wrapper; see also the limits below | One line at the registration site | Ours. |
| `PRAGMA foreign_keys = ON` inside the migration | | Everything: it is a no-op there | | Ruled out by the talk's own test |
| `PRAGMA defer_foreign_keys` | | Everything: it defers enforcement, and there is none | | Ruled out on the `sqlite3` shell |
| Enable enforcement in `onConfigure`, before the transaction | The forgotten `DELETE` would cascade | Every table rebuild then deletes child rows | A custom open helper | Rejected: with enforcement on, a rebuild loses child rows (measured) |
| `DELETE` and never `DROP` | The id reuse, while `AUTOINCREMENT` and its counter last | The orphans are still there, and the next table rebuild re-attaches them | | Ruled out by the talk's `WouldDeleteHaveHelpedTest` |
| Check and throw, as Room's auto-migrations do | Fails the upgrade loudly | Nothing is repaired; on a user's device a failed upgrade is a crash at start | | Not built. Right for a migration test, wrong for production. |
| Sweep in `onOpen` on every launch | Also orphans from earlier releases | Scans every child table at every start | | Not built. |

The walker and the sweep answer different questions. The walker clears a named subtree. The
sweep removes whatever lost its parent, anywhere. Both remain in the repository.

Limits of the sweep, all stated in the code or the pull requests:

- It deletes. For a foreign key declared `SET NULL` or `NO ACTION` that is not what the schema
  asked for. Every foreign key here is `CASCADE`.
- It covers the whole database, so it also removes orphans an earlier release left behind.
- It runs after every wrapped step. A migration that leaves orphans for a later step to
  re-parent would lose them.
- It reaches rows by rowid, under a name the table has left free. Two schemas defeat that. A
  generated column named `rowid` is invisible to `table_info`
  ([shell](evidence/sqlite-generated-rowid-column.txt)); `table_xinfo` would show it, but
  does not exist before SQLite 3.26, which `minSdk` 23 still reaches. And a table that
  declares all three names leaves none; the sweep then fails with an exception.
- The wrapper overrides `migrate(SupportSQLiteDatabase)`. With a `SQLiteDriver` set, Room calls
  the other overload and the wrapper fails with Room's `NotImplementedError`.

## The fix in code

```kotlin
fun SupportSQLiteDatabase.clearRowsWithoutParent() {
    var previous: List<Pair<String, Long>>? = null
    while (true) {
        val violations = readForeignKeyViolations()
        if (violations.isEmpty()) return
        check(violations != previous) { "Deleting by rowid removed none of $violations" }
        violations.groupBy({ it.first }, { it.second }).forEach { (table, rowIds) ->
            val rowIdName = rowIdNameOf(table)
            rowIds.forEach { execSQL("DELETE FROM $table WHERE $rowIdName = $it") }
        }
        previous = violations
    }
}

/** A name that still reaches the real rowid: `rowid`, `_rowid_` or `oid`, whichever the table has not declared. */
private fun SupportSQLiteDatabase.rowIdNameOf(table: String): String { /* reads PRAGMA table_info */ }

fun Migration.thenClearRowsWithoutParent(): Migration = object : Migration(startVersion, endVersion) {
    override fun migrate(db: SupportSQLiteDatabase) {
        this@thenClearRowsWithoutParent.migrate(db)
        db.clearRowsWithoutParent()
    }
}
```

Registered once:

```kotlin
.addMigrations(*migrations.map { it.thenClearRowsWithoutParent() }.toTypedArray())
```

The loop always ends. A pass that deletes a row shrinks the database. A pass that deletes
nothing would see the same report again, and stops there.

## Tests

Only the tests this fork added are listed. The talk's are described in the root README.

| Test | Why it exists | How it proves it |
|---|---|---|
| `clears the whole chain under an orphan, not just its first level` | One pass is not enough on three levels | Fails on upstream code with `expected:<1> but was:<2>`: the rid survives. Passes with the loop. |
| `clears a chain inside a self-referencing table` | A schema the walker cannot take | Fails on upstream code the same way |
| `removes the orphan and not a valid row when a column is named rowid` | A declared `rowid` column takes the name over | Before the fix the sweep removed the valid row and the guard stopped it with `Deleting by rowid removed none of [(Tag, 1)]`. After it, the valid row stays and the orphan goes. |
| `stops instead of looping when a pass removes nothing` | The loop must end on every input | A trigger vetoes the delete. With the guard removed the sweep never returns; the test fails after its own 5 s bound |
| `Room runs a migration before it has turned foreign keys on` | The mechanism, on a real Room upgrade of a file database | Reads the pragma inside `migrate()` and after open: 0, in a transaction, then 1 |
| `the broken migration orphans the products on a real upgrade` | The bug without any hand-toggled pragma | Room itself runs the talk's migration: 0 orders, 2 products |
| `the same migration followed by the sweep leaves no orphans` | The fix on the real path | The same migration, wrapped: 0 and 0 |
| `sweeping after the broken migration leaves nothing to re-attach` | The fix against the talk's verbatim three-table migration | The new order carries only `NEW-RID` |
| `with foreign keys on a table rebuild fires the cascade and empties the child` | Why enforcement cannot simply be on | The rebuild keeps the parent and loses the child |

The real upgrade is built without a second schema history: `TalkDatabase` creates the file, the
version is set to 42 through `openHelper.writableDatabase.version`, and a test-only
`TalkDatabaseV43` with the same entities opens it with a 42 to 43 migration.

## Gotchas

- `foreign_key_check` reports a row only when its immediate parent is missing.
- It works with enforcement off and inside a transaction. It is a query, not enforcement.
- If the parent table does not exist at all, every child row is reported. It is not an error.
- For a `WITHOUT ROWID` table the reported rowid is NULL, and `DELETE ... WHERE rowid` fails
  with `no such column: rowid`. So the sweep fails loudly there; it does not loop.
- A table with its own column named `rowid` hides the real one. A delete by that name
  matches nothing, or the wrong row. The sweep therefore asks `table_info` and uses whichever
  of `rowid`, `_rowid_`, `oid` the table has left free.
- A `BEFORE DELETE` trigger with `RAISE(IGNORE)` vetoes a delete without an error. The
  report then never changes; the guard exists for inputs like this.
- `DROP TABLE` removes the table's `sqlite_sequence` row; `DELETE` keeps it. That is why the
  orphans re-attached only after a drop.
- Room rewrites its identity hash after a successful migration, so a file created by one
  database class opens under another with the same tables.
- A second `@Database` class in the test sources needs `kspTest`, and `kspAndroidTest` for
  the instrumented copy.

## Evidence

On the `sqlite3` shell, 3.54.0 ([script](evidence/sqlite-experiments.sh), [output](evidence/sqlite-experiments.out)):

```
=== E5 self-referencing table: one pass vs fixpoint ===
pass 1:
n|2|n|0
pass 2:
n|3|n|0
pass 3:
n|4|n|0
pass 4 (empty = clean):
```

The new tests against upstream's single pass ([messages](evidence/orphan-sweep-before-fix-messages.txt),
[console log](evidence/orphan-sweep-before-fix.log)):

```
FAIL clears the whole chain under an orphan, not just its first level | java.lang.AssertionError: expected:<1> but was:<2>
ok   clears exactly the rows the migration orphaned
FAIL clears a chain inside a self-referencing table | java.lang.AssertionError: expected:<1> but was:<2>
ok   does nothing when every row still has its parent
```

That run had a third failing test, for a guard against `WITHOUT ROWID` tables. Its failure
showed that SQLite already rejects the delete there, so the guard and the test were dropped.

The loop without its guard, on a table with a `rowid` column
([log](evidence/orphan-sweep-hang-without-guard.log)): the Gradle run was killed after 120 s
with no test result written. That was before the sweep chose its rowid name.

The sweep against a declared integer `rowid` column, before that fix
([messages](evidence/orphan-sweep-shadowed-rowid-before-fix-messages.txt),
[shell session](evidence/sqlite-shadowed-rowid-deletes-valid-row.txt)):

```
FAIL removes the orphan and not a valid row when a column is named rowid | java.lang.IllegalStateException: Deleting by rowid removed none of [(Tag, 1)]
```

On the emulator ([results](evidence/device-main-run-45016675327.xml)): all three
`RealRoomMigrationTest` cases pass on Android 15.

## Not verified

- A physical device, and API levels other than 35.
- Room with a `SQLiteDriver` set.
- A multi-step upgrade path through the wrapper.
