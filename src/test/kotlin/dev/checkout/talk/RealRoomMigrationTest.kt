package dev.checkout.talk

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bug 2 on the real path: a file database upgraded by Room itself, with nobody toggling
 * the pragma by hand.
 *
 * Room does not switch `foreign_keys` off for a migration. By default every SQLite connection
 * opens with it off, the migration runs inside a transaction where the pragma cannot be
 * changed, and Room's generated `onOpen` switches it on only after that transaction has
 * committed.
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class RealRoomMigrationTest {

    private lateinit var context: Context
    private lateinit var db: TalkDatabaseV43

    /** The previous app version: one order with two products, stored at schema version 42. */
    @Before
    fun seedVersion42() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().context
        context.deleteDatabase(NAME)
        val old = Room.databaseBuilder(context, TalkDatabase::class.java, NAME).build()
        val orderId = old.orders().insert(OrderEntity())
        old.orders().insert(OrderProductEntity(orderId = orderId))
        old.orders().insert(OrderProductEntity(orderId = orderId))
        old.openHelper.writableDatabase.version = 42 // where the talk's migration starts
        old.close()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `Room runs a migration before it has turned foreign keys on`() {
        var foreignKeysInsideMigrate = -1
        var inTransaction = false
        upgrade(object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                foreignKeysInsideMigrate = db.foreignKeysFlag()
                inTransaction = db.inTransaction()
            }
        })

        val foreignKeysAfterOpen = db.openHelper.writableDatabase.foreignKeysFlag()

        assertEquals(0, foreignKeysInsideMigrate) // still off: nothing has turned it on yet
        assertTrue(inTransaction)                 // and in here the pragma cannot be changed
        assertEquals(1, foreignKeysAfterOpen)     // Room's generated onOpen, after the commit
    }

    /** The talk's migration, registered with Room and run by Room. */
    @Test
    fun `the broken migration orphans the products on a real upgrade`() = runBlocking {
        upgrade(BrokenOrderQueueMigration())

        assertEquals(0, db.orders().orderCount())
        assertEquals(2, db.orders().productCount()) // the cascade never fired
    }

    private fun upgrade(migration: Migration) {
        db = Room.databaseBuilder(context, TalkDatabaseV43::class.java, NAME)
            .addMigrations(migration)
            .build()
    }

    private fun SupportSQLiteDatabase.foreignKeysFlag(): Int =
        query("PRAGMA foreign_keys").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val NAME = "real-room-migration.db"
    }
}

/** The same tables as [TalkDatabase] one schema version later, so Room has a migration to run. */
@Database(
    entities = [OrderRow::class, OrderEntity::class, OrderProductEntity::class],
    version = 43,
    exportSchema = false,
)
abstract class TalkDatabaseV43 : RoomDatabase() {
    abstract fun orders(): OrderDao
}
