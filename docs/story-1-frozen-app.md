# Story 1: the frozen app

Tests: [FrozenAppTest](../src/test/kotlin/dev/checkout/talk/FrozenAppTest.kt),
[FrozenAppTopologyTest](../src/test/kotlin/dev/checkout/talk/FrozenAppTopologyTest.kt).

## The bug

The order-save transaction awaited a value it could only get by going back to the database and,
three layers down, to the network. The transaction stayed open for the whole wait, other database
calls queued behind it, and the app froze.

## The mechanism, layer by layer

The talk's comment says the transaction "holds the write lock ... so every other database call
queues behind it". That is true for the database the tests use. It is one of three cases.

| Layer | What it does | Kind |
|---|---|---|
| Room, `withTransaction` | Occupies Room's serial `TransactionExecutor` until it returns. Suspending DAO writes from other coroutines are dispatched to that same executor, so they queue inside Room. | Read: room-runtime 2.8.4 |
| Room, pool | With no `SQLiteDriver` set, Room uses a pass-through pool with "no real connection management logic". Reads go straight to the framework. | Read: room-runtime 2.8.4 |
| Framework, `SQLiteConnectionPool` | One connection unless the journal mode is WAL. A transaction holds the primary connection until it ends. A read may use another connection only where one exists. | Read through DeepWiki, then measured |
| Framework, waiting | A blocked call waits in Java, in `waitForConnection`, with no timeout. There is no `SQLITE_BUSY` to catch. | Read through DeepWiki only |
| Room, default journal mode | `AUTOMATIC`: write-ahead logging unless the device is low on RAM. | Read: room-runtime 2.8.4 |

Measured, the same on a macOS host, on Linux CI and on an Android 15 emulator:

| Database | Read from another scope | Write from another scope |
|---|---|---|
| In memory | waits | waits |
| File, rollback journal (`TRUNCATE`) | waits | waits |
| File, write-ahead log | does not wait | waits |

The WAL read took 33 to 42 ms on the host, 34 ms on Linux CI and 67 to 100 ms on the emulator.
Every waiting call was held for the full 2 s bound and finished once the transaction committed.

Two consequences:

- `FrozenAppTest` runs on an in-memory database, the first row. A default Room database on most
  devices is the third row.
- On such a device the deadlock in `a transaction awaiting a database-backed flow never returns`
  needs a producer that writes, or opens a transaction of its own. A producer that only reads
  would get through. This is reasoned from the table; no test here builds that exact shape on
  a WAL file.

## Strategies compared

| Strategy | Catches | Misses | Cost | Status |
|---|---|---|---|---|
| **Marker in the coroutine context**, checked in the network layer | A request made in the transaction's own coroutine, at run time, with a clear message | A request made by another scope while the transaction awaits its result, which is the shape that froze the app. Also anything that is not a network call. | A context element and one check in the network layer | The talk's fix. Kept. |
| **Gather first, write in a transaction that cannot suspend** (`runInTransaction` with non-suspending DAO writes) | Every suspending call inside the transaction, from any scope, at compile time | Blocking waits: `runBlocking`, a synchronous HTTP client, `Future.get()` | Writes need non-suspending DAO functions; the call needs a background dispatcher | Ours. Added next to the marker. |
| A restricted scope with `@RestrictsSuspension` | | Would also forbid the suspending DAO calls it is meant to wrap | | Rejected before building. See below. |
| Rely on write-ahead logging | Reads no longer wait | Writes still wait; low-RAM devices fall back to a rollback journal | none | Not a fix. It only narrows the bug. |
| Detect Room's own `TransactionElement` | | The class is `internal` | | Not possible. Read: room-runtime 2.8.4. |
| Give Room a `SQLiteDriver`, so it uses its own pool | Unknown | Unknown | A new dependency | Not tried. |

The two that exist are complements, not rivals. The marker is a cheap net under code that already
exists. The non-suspending body is a rule the compiler keeps for new code.

Why `@RestrictsSuspension` was dropped: the idea was a write scope whose own members may call
suspending DAO functions while the block may not suspend otherwise. DeepWiki, in fast mode against
the Kotlin compiler, answered that member functions of a restricted type are restricted too, and
cited the checker and its test data. We did not confirm that by compiling. Room's `runInTransaction`
made the question moot: it already takes a plain block.

## The fix in code

```kotlin
val shipping = shippings.first()                // 1. gather: free to read the database
val stocks = requestStocks(url)                 // 2. network: no transaction is open
withContext(Dispatchers.IO) {
    db.runInTransaction {                       // 3. write: this block cannot suspend
        db.orderDao().insertNow(row)
    }
}
```

Putting `shippings.first()` inside the block does not compile
([log](evidence/suspend-call-in-runInTransaction-compile-error.log)):

```
e: src/test/kotlin/dev/checkout/talk/FrozenAppTest.kt:173:31 Suspension functions can only be called within coroutine body.
```

## Tests

| Test | From | Why it exists | How it proves it |
|---|---|---|---|
| `a transaction awaiting a database-backed flow never returns` | talk | The shape that froze the app | Bounded by `withTimeoutOrNull(3.seconds)`; the result is null |
| `a slow call inside a transaction blocks every other database call` | talk | The lock is held for the whole request | A read started inside the transaction has not finished after the delay |
| `the marker turns the freeze into a loud failure` | talk | The marker fix | The guarded request throws inside `guardedTransaction` |
| `the marker does not see a request made by the awaited flow` | ours | The marker's blind spot | Same guard, same transaction, but the request runs in the producer's scope: no error, and the transaction returns the answer |
| `gathering before a transaction that cannot suspend leaves nothing to wait for` | ours | The fix by construction | The producer that deadlocks in the first test now completes; an unrelated read is awaited while the request is in flight; one row is written |
| Six tests in `FrozenAppTopologyTest` | ours | Who waits, per database kind | See below |

The topology helper opens a transaction, starts the other call in another scope, waits until
that coroutine has started, and only then gives it 2 s. After the transaction ends it requires
the call to finish. So "waits" means: it had started, it did not finish while the transaction
was open, and it finished once the transaction was gone.

## Gotchas

- An in-memory database hides the journal mode. It always has one connection.
- "Did not finish in 2 s" is not "was waiting" unless the call is known to have started. The
  first version of the helper did not check that. A reviewer caught it.
- `Job.isCompleted` is also true for a failed or cancelled job. Await the job instead.
- `runInTransaction` and non-suspending DAO functions refuse the main thread, hence
  `withContext(Dispatchers.IO)`.
- "Cannot suspend" is not "cannot wait". The first wording of the test comment claimed the
  second. A reviewer caught that too.

## Evidence

- Host, three runs: [1](evidence/topology-host-run1.xml), [2](evidence/topology-host-run2.xml), [3](evidence/topology-host-run3.xml).
- Emulator, API 35: [device results from `main`](evidence/device-main-run-45016675327.xml).
- Compile error: [log](evidence/suspend-call-in-runInTransaction-compile-error.log).

## Not verified

- A physical device and API levels other than 35. The WAL pool size comes from a device resource.
- Room with a `SQLiteDriver` set, where Room manages its own connections.
- A deadlock on a WAL file caused by a producer that writes. The table predicts it; no test builds it.
