# R11.5.2-R4 migration and concurrency certification

## Schema decision

Room remains at v8. No new persisted concept was required: v8 already separates canonical
identity rows from exact-context decisions. A v9 marker could not reconstruct previously
broadened evidence safely. Runtime validation therefore accepts overlap evidence only when
its album hash reproduces the canonical group; uncertain legacy rows remain available by
exact key but cannot claim another subset.

All v1→v8 migrations remain additive and non-destructive. Run 0010 passed six migration
tests, including v7→v8 decision-table creation and preservation of identities, profiles,
artwork selections/focus, provenance, licence, and meaningful decisions.

## Transaction and race evidence

Identity save, profile save, exact resolution, and selective clear use Room transactions;
association writes use `@Upsert`, not SQLite `REPLACE`. Eight file-backed tests cover
same/different MBID races, cancellation rollback, clear ordering, biography preservation,
library isolation, reopen, integrity check, and foreign-key check. Run 0023 passed the
37-test integrated persistence/restart/transaction/artwork set.

Controller request epochs and cancellation prevent late prior-library work from changing
the visible current context. Explicit search remains cancellable and does not run from
bind or recomposition.

`ARTIST_TRANSACTION_GO = PASS` and `ARTIST_MIGRATION_GO = PASS`.
