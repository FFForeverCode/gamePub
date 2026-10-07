# Task 3 Report: Generation Persistence

## Status

Implemented and committed generation identity, request idempotency, fencing epochs, and transaction-oriented persistence APIs. The implementation is forward-only in V2; V1 is unchanged.

## Changes

- Added `chat_generations` with unique `(conversation_id, client_request_id)` and assistant-message constraints, retry linkage, token usage, error code, lifecycle timestamps, and recovery lookup index.
- Added `conversations.generation_epoch` and nullable `messages.generation_id` with foreign keys, preserving historical rows as unlinked.
- Added generation model/status and MyBatis create, idempotent lookup, active-row lock, conditional epoch/status transition, and stale-running lookup APIs.
- Added conversation row lock and epoch increment/read operations. The active-generation `FOR UPDATE` lookup is documented to run inside a transaction after locking the parent conversation.
- Added message linking after generation insertion, plus assistant completion guarded by generation ID, epoch, expected generation status, and streaming message status.
- Added MySQL integration coverage for schema/backward compatibility, idempotency uniqueness, message linkage, stale fencing/status rejection, parent locking/epoch allocation, terminal write rollback, and stale-running lookup.

## Verification

- Test-first focused run: `mvn -Dtest=GenerationPersistenceTest test` failed at test compilation because the generation mapper/model and new lock/link APIs did not yet exist.
- Focused verification: `mvn -Dtest=GenerationPersistenceTest test` passed, 7 tests, 0 failures/errors/skips. Testcontainers ran MySQL 8.4 and Flyway applied V1 and V2.
- Full backend verification: `mvn test` passed, 17 tests, 0 failures/errors/skips.
- `git diff --check` passed. No V1 migration change. The pre-existing untracked `.m2-classpath` was not modified or staged.

## Notes

Generation creation and generation/message terminal writes are transaction-composable mapper operations, not service-level transaction methods in this task. Callers must invoke creation/linking and paired terminal updates in one MySQL transaction; the test verifies atomic rollback when composed with `TransactionTemplate`. Existing JDK/Netty test-runtime warnings were non-fatal.

## Commit

`feat: persist idempotent chat generations`
