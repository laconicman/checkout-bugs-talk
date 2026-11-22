package dev.checkout.talk

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase.JournalMode
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Device copy of the test of the same name in src/test; keep the two in step. Test names
 * use underscores here because DEX below version 040 rejects spaces in class names.
 *
 * Bug 1, one layer down: which calls wait for an open transaction, and where.
 *
 * Room queues transactions and writes on one executor. Underneath, the framework keeps a
 * pool of SQLite connections: a single one for an in-memory database and for a
 * rollback-journal file, several under write-ahead logging. A transaction holds the primary
 * connection until it ends.
 *
 * So a write from another coroutine always waits. A read waits as well, unless the database
 * uses write-ahead logging, where it gets a connection of its own.
 */
@RunWith(AndroidJUnit4::class)
class FrozenAppTopologyTest {

    private lateinit var context: Context
    private lateinit var elsewhere: CoroutineScope
    private lateinit var db: TalkDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().context
        context.deleteDatabase(NAME)
        elsewhere = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        elsewhere.cancel()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun in_memory_a_read_from_another_scope_waits_for_the_transaction() = runBlocking {
        db = inMemory()
        assertFalse(finishesWhileTransactionIsOpen { orderDao().count() })
    }

    @Test
    fun in_memory_a_write_from_another_scope_waits_for_the_transaction() = runBlocking {
        db = inMemory()
        assertFalse(finishesWhileTransactionIsOpen { orderDao().insert(OrderRow(2)) })
    }

    @Test
    fun on_a_rollback_journal_file_a_read_from_another_scope_waits_for_the_transaction() = runBlocking {
        db = file(JournalMode.TRUNCATE)
        assertFalse(finishesWhileTransactionIsOpen { orderDao().count() })
    }

    @Test
    fun on_a_rollback_journal_file_a_write_from_another_scope_waits_for_the_transaction() = runBlocking {
        db = file(JournalMode.TRUNCATE)
        assertFalse(finishesWhileTransactionIsOpen { orderDao().insert(OrderRow(2)) })
    }

    @Test
    fun on_a_write_ahead_log_file_a_read_from_another_scope_does_not_wait() = runBlocking {
        db = file(JournalMode.WRITE_AHEAD_LOGGING)
        assertTrue(finishesWhileTransactionIsOpen { orderDao().count() })
    }

    @Test
    fun on_a_write_ahead_log_file_a_write_from_another_scope_still_waits() = runBlocking {
        db = file(JournalMode.WRITE_AHEAD_LOGGING)
        assertFalse(finishesWhileTransactionIsOpen { orderDao().insert(OrderRow(2)) })
    }

    /**
     * Opens a transaction, starts [work] in another scope, and tells whether it finished before
     * the transaction did. Once the transaction ends the work must finish: it was waiting, not
     * broken.
     */
    private suspend fun finishesWhileTransactionIsOpen(work: suspend TalkDatabase.() -> Unit): Boolean {
        val started = CompletableDeferred<Unit>()
        lateinit var other: Deferred<Unit>
        val finished = db.withTransaction {
            db.orderDao().insert(OrderRow(1))
            other = elsewhere.async {
                started.complete(Unit)
                db.work()
            }
            withTimeout(5.seconds) { started.await() } // it is running: from here on it can only be waiting
            withTimeoutOrNull(2.seconds) { other.await() } != null
        }
        withTimeout(5.seconds) { other.await() }
        return finished
    }

    private fun inMemory() = Room.inMemoryDatabaseBuilder(context, TalkDatabase::class.java).build()

    private fun file(journalMode: JournalMode) =
        Room.databaseBuilder(context, TalkDatabase::class.java, NAME).setJournalMode(journalMode).build()

    private companion object {
        const val NAME = "frozen-app-topology.db"
    }
}
