package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Backs the slide version of the cleaner: it really does find and remove exactly the
 * rows a migration orphaned, and it needs no list of tables to do it.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class OrphanCleanerTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = {
        writeableDatabase(version = 1) {
            onCreate {
                execSQL("CREATE TABLE OrderEntity (id INTEGER PRIMARY KEY NOT NULL)")
                execSQL(
                    "CREATE TABLE OrderProductEntity (" +
                        "id INTEGER PRIMARY KEY NOT NULL, " +
                        "orderId INTEGER NOT NULL, " +
                        "FOREIGN KEY(orderId) REFERENCES OrderEntity(id) ON DELETE CASCADE" +
                        ")"
                )
            }
        }
    }
) {

    @Test
    fun `clears exactly the rows the migration orphaned`() {
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1), (2)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (10, 1), (11, 2)")

        // The migration clears one order and leaves its product behind.
        db.asMigration { execSQL("DELETE FROM OrderEntity WHERE id = 1") }
        assertEquals(2, db.countOf("OrderProductEntity"))

        db.clearRowsWithoutParent()

        // The orphan is gone; the product of the surviving order is untouched.
        assertEquals(1, db.countOf("OrderProductEntity"))
        assertEquals(1, db.countOf("OrderProductEntity", "orderId = 2"))
    }

    @Test
    fun `does nothing when every row still has its parent`() {
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (10, 1)")

        db.clearRowsWithoutParent()

        assertEquals(1, db.countOf("OrderEntity"))
        assertEquals(1, db.countOf("OrderProductEntity"))
    }

    /** `foreign_key_check` only reports a missing immediate parent, so one pass is not enough. */
    @Test
    fun `clears the whole chain under an orphan, not just its first level`() {
        db.execSQL(
            "CREATE TABLE OrderProductRid (" +
                "id INTEGER PRIMARY KEY NOT NULL, " +
                "productId INTEGER NOT NULL, " +
                "FOREIGN KEY(productId) REFERENCES OrderProductEntity(id) ON DELETE CASCADE" +
                ")"
        )
        db.execSQL("INSERT INTO OrderEntity (id) VALUES (1), (2)")
        db.execSQL("INSERT INTO OrderProductEntity (id, orderId) VALUES (10, 1), (11, 2)")
        db.execSQL("INSERT INTO OrderProductRid (id, productId) VALUES (100, 10), (101, 11)")

        db.asMigration { execSQL("DELETE FROM OrderEntity WHERE id = 1") }
        db.clearRowsWithoutParent()

        // Order 2 keeps its product and its rid; nothing of order 1 is left at any level.
        assertEquals(1, db.countOf("OrderProductEntity"))
        assertEquals(1, db.countOf("OrderProductEntity", "orderId = 2"))
        assertEquals(1, db.countOf("OrderProductRid"))
        assertEquals(1, db.countOf("OrderProductRid", "productId = 11"))
    }

    /** A table that references itself is a cycle in the schema; the sweep does not care. */
    @Test
    fun `clears a chain inside a self-referencing table`() {
        db.execSQL(
            "CREATE TABLE Category (" +
                "id INTEGER PRIMARY KEY NOT NULL, " +
                "parentId INTEGER, " +
                "FOREIGN KEY(parentId) REFERENCES Category(id) ON DELETE CASCADE" +
                ")"
        )
        db.execSQL("INSERT INTO Category (id, parentId) VALUES (1, NULL), (2, 1), (3, 2), (4, NULL)")

        db.asMigration { execSQL("DELETE FROM Category WHERE id = 1") }
        db.clearRowsWithoutParent()

        assertEquals(1, db.countOf("Category"))
        assertEquals(1, db.countOf("Category", "id = 4"))
    }

    /** A column named `rowid` hides the real one, so the delete finds nothing to remove. */
    @Test
    fun `stops instead of looping when a pass removes nothing`() = runBlocking {
        db.execSQL(
            "CREATE TABLE Tag (" +
                "rowid TEXT NOT NULL, " +
                "orderId INTEGER NOT NULL, " +
                "FOREIGN KEY(orderId) REFERENCES OrderEntity(id) ON DELETE CASCADE" +
                ")"
        )
        db.asMigration { execSQL("INSERT INTO Tag (rowid, orderId) VALUES ('gift', 1)") }

        // Off the test thread and bounded: without the guard this call would never return.
        val sweep = CoroutineScope(Dispatchers.IO).async { runCatching { db.clearRowsWithoutParent() } }
        val outcome = withTimeoutOrNull(5.seconds) { sweep.await() }

        assertTrue(outcome?.exceptionOrNull() is IllegalStateException)
    }

    private fun SupportSQLiteDatabase.asMigration(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("PRAGMA foreign_keys = OFF")
        block()
    }

    private fun SupportSQLiteDatabase.countOf(table: String, where: String? = null): Int {
        val sql = "SELECT COUNT(*) FROM $table" + if (where == null) "" else " WHERE $where"
        return query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }
}
