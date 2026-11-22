package dev.checkout.talk

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
        violations.forEach { (table, rowId) ->
            execSQL("DELETE FROM $table WHERE rowid = $rowId")
        }
        previous = violations
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
