package dev.checkout.talk

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Demo repair: ask SQLite which rows have missing parents, then delete those rows.
 * This version omits production concerns such as reporting and transaction handling.
 *
 * `foreign_key_check` reports a row only when its immediate parent is missing, and deleting
 * that row can orphan its own children, so repeat until the check comes back empty. A pass
 * that removes nothing would repeat forever, so it stops with an error instead.
 */
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

/**
 * The same migration followed by the repair, inside the transaction Room already opened.
 * Wrap migrations where they are registered and no author has to remember the children.
 */
fun Migration.thenClearRowsWithoutParent(): Migration = object : Migration(startVersion, endVersion) {
    override fun migrate(db: SupportSQLiteDatabase) {
        this@thenClearRowsWithoutParent.migrate(db)
        db.clearRowsWithoutParent()
    }
}

/**
 * Every row `foreign_key_check` reports as violating, as table name to rowid.
 *
 * Read fully before deleting anything: deleting while the pragma cursor is still open
 * is asking for trouble.
 */
private fun SupportSQLiteDatabase.readForeignKeyViolations(): List<Pair<String, Long>> =
    query("PRAGMA foreign_key_check").use { cursor ->
        val table = cursor.getColumnIndexOrThrow("table")
        val rowId = cursor.getColumnIndexOrThrow("rowid")
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(table) to cursor.getLong(rowId))
            }
        }
    }

/**
 * A name that still reaches the real rowid of [table]. SQLite knows it as `rowid`, `_rowid_`
 * and `oid`, and a declared column with one of these names takes that name over.
 */
private fun SupportSQLiteDatabase.rowIdNameOf(table: String): String {
    val declared = query("PRAGMA table_info(`$table`)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(name).lowercase()) }
    }
    return listOf("rowid", "_rowid_", "oid").first { it !in declared }
}
