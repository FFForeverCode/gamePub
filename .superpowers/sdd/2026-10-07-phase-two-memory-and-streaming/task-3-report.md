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

## Transaction Boundary Follow-up

### Changes

- Added `GenerationTerminalService.finish(...)` as the production transaction boundary for generation and assistant-message terminal writes.
- The service validates compatible terminal status pairs, checks the database fence epoch and expected generation state, and throws if either conditional update does not affect exactly one row so Spring rolls back the paired writes.
- Added integration coverage for paired completion, rollback when the assistant message cannot transition, and stale-epoch rejection.

### TDD Evidence

- RED: `mvn -Dtest=GenerationPersistenceTest test` failed at test compilation because `GenerationTerminalService` did not exist.
- GREEN: With Java 17, `mvn -Dtest=GenerationPersistenceTest test` passed: 10 tests, 0 failures, 0 errors, 0 skipped. MySQL 8.4 Testcontainers applied Flyway V1 and V2.
- Output includes an existing Netty macOS DNS native-library load error with fallback to system defaults and a JVM class-sharing warning. These did not fail the tests; no dependency or logging changes were made solely to suppress environment noise.

### Files Changed

- `server/src/main/java/com/gamepub/server/conversation/GenerationTerminalService.java`
- `server/src/test/java/com/gamepub/server/conversation/GenerationPersistenceTest.java`

### Self-Review

- Terminal state mappings are constrained to `COMPLETED` -> `COMPLETED`, `FAILED`/`RETRYABLE` -> `FAILED`, and `CANCELLED` -> `CANCELLED`.
- The assistant message ID is loaded from the generation row rather than supplied independently by callers.
- The transaction rolls back if the generation is stale or if the assistant message was already terminal.
