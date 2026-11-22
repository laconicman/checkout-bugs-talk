#!/bin/sh
# Engine-level facts behind story 2, on the host sqlite3 CLI.
echo "sqlite version: $(sqlite3 :memory: 'select sqlite_version()')"
run() { echo; echo "=== $1 ==="; sqlite3 :memory: 2>&1; }

run "E1 foreign_key_check works with FK OFF and inside BEGIN EXCLUSIVE; pragma toggle inside tx is ignored" <<'SQL'
CREATE TABLE p(id INTEGER PRIMARY KEY);
CREATE TABLE c(id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id) ON DELETE CASCADE);
INSERT INTO p VALUES(1); INSERT INTO c VALUES(1,1);
PRAGMA foreign_keys=OFF;
BEGIN EXCLUSIVE;
PRAGMA foreign_keys=ON;
SELECT 'fk flag inside tx', * FROM pragma_foreign_keys;
DELETE FROM p;
SELECT 'child rows after delete', count(*) FROM c;
SELECT 'check inside tx:'; PRAGMA foreign_key_check;
COMMIT;
SQL

run "E2 parent table dropped and NOT recreated" <<'SQL'
CREATE TABLE p(id INTEGER PRIMARY KEY);
CREATE TABLE c(id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id) ON DELETE CASCADE);
INSERT INTO p VALUES(1); INSERT INTO c VALUES(1,1);
PRAGMA foreign_keys=OFF;
DROP TABLE p;
SELECT 'check:'; PRAGMA foreign_key_check;
SELECT 'child rows', count(*) FROM c;
SQL

run "E3 WITHOUT ROWID child: rowid column" <<'SQL'
.nullvalue NULL
CREATE TABLE p(id INTEGER PRIMARY KEY);
CREATE TABLE c(k TEXT PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id)) WITHOUT ROWID;
PRAGMA foreign_keys=OFF;
INSERT INTO c VALUES('a',7);
PRAGMA foreign_key_check;
SQL

run "E4 FK ON: DROP TABLE parent cascades into children (why 'just enable FKs' breaks table rebuilds)" <<'SQL'
PRAGMA foreign_keys=ON;
CREATE TABLE p(id INTEGER PRIMARY KEY AUTOINCREMENT);
CREATE TABLE c(id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id) ON DELETE CASCADE);
INSERT INTO p VALUES(1); INSERT INTO c VALUES(1,1),(2,1);
CREATE TABLE _new_p(id INTEGER PRIMARY KEY AUTOINCREMENT, extra TEXT);
INSERT INTO _new_p(id) SELECT id FROM p;
DROP TABLE p;
ALTER TABLE _new_p RENAME TO p;
SELECT 'parents kept', count(*) FROM p;
SELECT 'children kept', count(*) FROM c;
SQL

run "E5 self-referencing table: one pass vs fixpoint" <<'SQL'
CREATE TABLE n(id INTEGER PRIMARY KEY, parent INTEGER REFERENCES n(id) ON DELETE CASCADE);
INSERT INTO n VALUES(1,NULL),(2,1),(3,2),(4,3);
PRAGMA foreign_keys=OFF;
DELETE FROM n WHERE id=1;
SELECT 'pass 1:'; PRAGMA foreign_key_check;
DELETE FROM n WHERE rowid=2;
SELECT 'pass 2:'; PRAGMA foreign_key_check;
DELETE FROM n WHERE rowid=3;
SELECT 'pass 3:'; PRAGMA foreign_key_check;
DELETE FROM n WHERE rowid=4;
SELECT 'pass 4 (empty = clean):'; PRAGMA foreign_key_check;
SQL

run "E6 sqlite_sequence: DELETE keeps it, DROP removes it" <<'SQL'
CREATE TABLE p(id INTEGER PRIMARY KEY AUTOINCREMENT);
INSERT INTO p DEFAULT VALUES; INSERT INTO p DEFAULT VALUES;
DELETE FROM p;
SELECT 'after DELETE', * FROM sqlite_sequence;
DROP TABLE p;
SELECT 'after DROP rows:', count(*) FROM sqlite_sequence;
SQL

run "E7 defer_foreign_keys while foreign_keys is OFF" <<'SQL'
CREATE TABLE p(id INTEGER PRIMARY KEY);
CREATE TABLE c(id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id) ON DELETE CASCADE);
INSERT INTO p VALUES(1); INSERT INTO c VALUES(1,1);
PRAGMA foreign_keys=OFF;
BEGIN;
PRAGMA defer_foreign_keys=ON;
DELETE FROM p;
COMMIT;
SELECT 'orphans survive commit', count(*) FROM c;
SQL
