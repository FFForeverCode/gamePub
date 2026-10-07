# Phase Two Chat, Memory, and Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evolve tik-agent into a locally runnable, multi-instance-ready chat service with Gemini support, Redis-backed short-term memory and generation coordination, MySQL-idempotent generation records, and resumable SSE event replay.

**Architecture:** Keep the modular Spring Boot application and existing React client. MySQL remains authoritative for conversations, messages, and generation state; Redis provides leases, bounded memory windows, per-generation snapshots, and replayable Redis Streams. A model-provider boundary normalizes Mock, Gemini, and OpenAI-compatible output; database epochs and conditional updates fence terminal writes.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1 BOM, Spring Data Redis, Redis Streams/Lua, MyBatis, MySQL 8.4, Flyway, Testcontainers, React 19, TypeScript, Vitest, Docker Compose.

**Spec:** `docs/技术方案/二期技术方案说明.md`

All paths under task `Files` sections are relative to the `tik-agent/` project root. Shell commands start at the repository root unless the command block explicitly changes directory.

## Global Constraints

- Keep the application single-user and unauthenticated; do not add users, tenants, permissions, RAG, or long-term memory.
- Keep the existing Mock model working without credentials and preserve OpenAI-compatible model support.
- Use the Spring AI BOM version `2.0.1`; do not override managed Spring AI module versions individually.
- Keep MySQL as the authoritative source for completed conversation history and generation terminal state.
- Treat Redis Streams as bounded replay storage, not a permanent event log or a source of truth.
- Use Redis leases for coordination, but use MySQL row locking, monotonic generation epochs, and conditional updates to protect persisted state.
- Do not claim that a provider model stream can migrate to another process. Client reconnect can attach to Redis events; an interrupted model attempt becomes retryable and explicit retry creates a new generation.
- Store no real API keys in source, test fixtures, Redis, MySQL, logs, generated docs, or `.env.example`; inject secrets only at runtime.
- Do not modify Flyway V1; add forward-only migrations.
- A Redis delta must be persisted before it is sent to a browser. Retention trimming must never create a replay gap that is presented as a successful `resync`.
- Preserve existing MySQL conversation/message APIs and SSE event behavior where compatible; document any additive protocol changes.
- Run Maven commands from `tik-agent/server` and npm commands from `tik-agent/ui`.
- Do not stage or revert unrelated workspace changes. Commit only files listed in the current task.

---

## Repository Map

### Existing files to evolve

- `server/pom.xml`: Spring AI Google GenAI, Spring Data Redis, and Testcontainers Redis dependencies; versions remain BOM-managed where possible.
- `server/src/main/resources/application.yml`: Redis, provider, memory-window, checkpoint, generation, and recovery settings.
- `server/src/main/java/com/gamepub/server/config/TikAgentProperties.java`: typed configuration records and validation defaults.
- `server/src/main/java/com/gamepub/server/agent/AgentClient.java`: normalized streaming output contract.
- `server/src/main/java/com/gamepub/server/agent/SpringAiAgentConfig.java`: provider-specific client construction.
- `server/src/main/java/com/gamepub/server/agent/ModelCatalog.java`: configured model lookup and public model descriptors.
- `server/src/main/java/com/gamepub/server/chat/ChatService.java`: prompt preparation and generation lifecycle integration.
- `server/src/main/java/com/gamepub/server/chat/ChatStreamService.java`: current in-memory stream orchestration to be decomposed around generation/checkpoint services.
- `server/src/main/java/com/gamepub/server/chat/ShortTermMemory.java`: storage boundary to evolve from raw list access to bounded context snapshots.
- `server/src/main/java/com/gamepub/server/conversation/ConversationService.java`: existing conversation/message transaction and sequence behavior.
- `server/src/main/java/com/gamepub/server/conversation/MessageMapper.java` and `server/src/main/resources/mapper/MessageMapper.xml`: conditional message persistence operations.
- `server/src/main/resources/db/migration/`: append V2 schema only.
- `docker-compose.yml`, `.env.example`, `docs/部署说明.md`, `docs/接口说明.md`, and `docs/工作进度/二期工作.md`: operational and contract documentation.
- `ui/src/api/chatStream.ts`, `ui/src/api/sseParser.ts`, `ui/src/hooks/useChatWorkspace.ts`, and `ui/src/types/api.ts`: generation IDs, reconnect cursors, resync, cancellation, and retry UI state.

### New files expected

- Agent: `agent/AgentStreamChunk.java`, `agent/GoogleGenAiAgentClient.java`, and focused provider tests.
- Persistence: `conversation/ChatGeneration.java`, `GenerationStatus.java`, `GenerationMapper.java`, mapper XML, and generation service/tests.
- Memory: `memory/MemoryWindow.java`, `MemoryWindowService.java`, `RedisShortTermMemory.java`, estimator abstraction, and tests.
- Coordination: `chat/GenerationLease.java`, `RedisGenerationLease.java`, and lease integration tests.
- Streaming: `stream/StreamContext.java`, `StreamEvent.java`, `StreamCheckpointCoordinator.java`, `RedisGenerationStreamStore.java`, `GenerationReplayService.java`, and focused tests.
- API: generation replay/cancel endpoints and response DTOs.
- UI: focused stream reconnect tests and any small state/helper modules needed to keep `useChatWorkspace.ts` readable.

Avoid creating all of these files in a single scaffolding task. Each task below introduces only the types needed for its own tested behavior.

## Dependency and Delivery Order

1. Baseline and infrastructure can be delivered without enabling Gemini.
2. Model adapter and MySQL generation persistence are independent after the baseline and can be reviewed separately.
3. Redis memory and Redis leases depend on baseline; stream checkpoint depends on generation persistence and lease contracts.
4. SSE API and UI reconnect depend on the event store and checkpoint semantics.
5. Recovery and final acceptance depend on all previous tasks.

This is one end-to-end phase plan because the protocol and failure semantics cross these modules. Each task still produces an independently testable increment. If an implementation team wants separate PR tracks, split only after agreeing the exact contracts defined below.

## Shared Interfaces

Use these contracts consistently across tasks:

```java
public record AgentStreamChunk(
        String text,
        String finishReason,
        Long inputTokenCount,
        Long outputTokenCount) {
}
```

```java
public enum GenerationStatus {
    CREATED, RUNNING, CANCEL_REQUESTED, COMPLETED, FAILED, CANCELLED, RETRYABLE
}
```

```java
public record GenerationRequest(
        long conversationId,
        String clientRequestId,
        String content,
        String modelId) {
}
```

```java
public record MemoryWindow(
        List<AgentMessage> messages,
        int estimatedTokens,
        long newestSequenceNo) {
}
```

```java
public record GenerationIdentity(
        long conversationId,
        long generationId,
        long fenceEpoch,
        String ownerToken) {
}

public record AppendedEvent(
        String streamId,
        StreamEvent event,
        boolean checkpointed) {
}
```

All streaming/replay event IDs are opaque Redis Stream ID strings. The browser must not parse them as numbers.

## Task 1: Redis and Provider Configuration Baseline

**Files:**
- Modify: `server/pom.xml`
- Modify: `server/src/main/resources/application.yml`
- Modify: `server/src/main/java/com/gamepub/server/config/TikAgentProperties.java`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Test: `server/src/test/java/com/gamepub/server/config/TikAgentPropertiesTest.java`
- Test: `server/src/test/java/com/gamepub/server/support/RedisIntegrationTest.java`

**Interfaces:**
- Produces typed Redis, Gemini, memory-window, checkpoint, lease, and generation settings consumed by subsequent tasks.
- Produces a Redis Testcontainers base fixture parallel to the existing MySQL fixture.
- The Mock-only profile remains bootable when Redis is available and no Gemini credentials are set.

- [ ] **Step 1: Add configuration binding tests**

Create `TikAgentPropertiesTest` to bind representative properties and assert defaults for:

```text
memory.maxMessages = 20
memory.tokenBudget > 0
stream.checkpointTokenThreshold = 32
stream.checkpointInterval = 1s
generation.leaseTtl > generation.leaseRenewInterval
agent.models.mock.enabled = true
agent.models.gemini.enabled = false
```

Also assert invalid lease settings fail startup validation, including renewal interval greater than or equal to lease TTL.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run:

```bash
cd tik-agent/server
mvn -Dtest=TikAgentPropertiesTest test
```

Expected: test fails because the typed configuration records and properties do not yet exist.

- [ ] **Step 3: Add managed dependencies and typed properties**

Add the Spring AI Google GenAI starter and Spring Boot Redis starter without explicit versions if managed by the current BOM. Add Testcontainers Redis as a test-scoped dependency, using the project’s existing Testcontainers version management.

Extend `TikAgentProperties` with nested records for Redis-independent typed settings:

```java
record Memory(int maxMessages, int tokenBudget, Duration expireAfterAccess, long maximumSize) {}
record Stream(int checkpointTokenThreshold, Duration checkpointInterval,
              int eventMaxLength, Duration eventTtl, int maxOutputBytes) {}
record Generation(Duration leaseTtl, Duration leaseRenewInterval,
                  Duration timeout, int maxConcurrentPerInstance) {}
```

Add Gemini fields to the model configuration while retaining current OpenAI fields. Add Bean Validation constraints for positive limits and `leaseRenewInterval < leaseTtl`.

- [ ] **Step 4: Configure Redis and Compose defaults**

Add a Redis service to `docker-compose.yml` with a health check and a named data volume for local persistence. Configure the server to use the Compose service hostname and wait for Redis health. Add empty Gemini variables and Redis/memory/stream/generation defaults to `.env.example`; do not add credentials.

- [ ] **Step 5: Add Redis test fixture and verify configuration**

Create a test fixture using `GenericContainer<?>` with a pinned Redis image supported by the project’s Redis command set. Add dynamic properties for host and mapped port. Rerun:

```bash
cd tik-agent/server
mvn -Dtest=TikAgentPropertiesTest test
cd ..
docker compose config
```

Expected: focused tests pass; Compose renders without requiring a real Gemini key.

- [ ] **Step 6: Commit the baseline**

```bash
git add tik-agent/server/pom.xml tik-agent/server/src/main/resources/application.yml tik-agent/server/src/main/java/com/gamepub/server/config/TikAgentProperties.java tik-agent/server/src/test/java/com/gamepub/server/config/TikAgentPropertiesTest.java tik-agent/server/src/test/java/com/gamepub/server/support/RedisIntegrationTest.java tik-agent/docker-compose.yml tik-agent/.env.example
git commit -m "chore: add phase two redis and provider configuration"
```

## Task 2: Normalize Model Streaming and Add Gemini

**Files:**
- Create: `server/src/main/java/com/gamepub/server/agent/AgentStreamChunk.java`
- Create: `server/src/main/java/com/gamepub/server/agent/GoogleGenAiAgentClient.java`
- Modify: `server/src/main/java/com/gamepub/server/agent/AgentClient.java`
- Modify: `server/src/main/java/com/gamepub/server/agent/SpringAiAgentClient.java`
- Modify: `server/src/main/java/com/gamepub/server/agent/MockAgentClient.java`
- Modify: `server/src/main/java/com/gamepub/server/agent/SpringAiAgentConfig.java`
- Modify: `server/src/main/java/com/gamepub/server/agent/ModelCatalog.java`
- Test: `server/src/test/java/com/gamepub/server/agent/AgentClientContractTest.java`
- Test: `server/src/test/java/com/gamepub/server/agent/GoogleGenAiAgentClientTest.java`
- Test: `server/src/test/java/com/gamepub/server/agent/ModelCatalogTest.java`

**Interfaces:**
- Consumes typed model configuration from Task 1.
- Produces `AgentClient.stream(List<AgentMessage>) -> Flux<AgentStreamChunk>`.
- `AgentStreamChunk.text` is non-null; optional usage and finish metadata are populated only when supplied by the provider.

- [ ] **Step 1: Write normalized streaming contract tests**

Test that Mock output is converted into ordered `AgentStreamChunk` values, that concatenating `text` reproduces the complete Mock answer, and that only the terminal chunk may include finish metadata. Test `SpringAiAgentClient` mapping for system/user/assistant roles and provider usage extraction with mocked Spring AI responses.

- [ ] **Step 2: Run the focused tests and confirm they fail**

```bash
cd tik-agent/server
mvn -Dtest=AgentClientContractTest,GoogleGenAiAgentClientTest,ModelCatalogTest test
```

Expected: compilation/test failure because the chunk type and Gemini client do not exist.

- [ ] **Step 3: Introduce the chunk contract and adapt existing clients**

Add `AgentStreamChunk` and change `AgentClient.stream` to return `Flux<AgentStreamChunk>`. Update Mock and OpenAI-compatible implementations. Preserve text chunk ordering and use one terminal metadata chunk or an explicit empty-text terminal item, then cover that convention in the contract test.

- [ ] **Step 4: Implement the Google GenAI adapter**

Build the Google GenAI `ChatModel` using Spring AI 2.0.1-managed APIs. Map domain messages to Spring AI messages, expose text chunks and available usage/finish reason, and keep raw provider objects inside the adapter. Configure Developer API Key from the Gemini model config; allow Vertex AI credentials through supported Spring AI configuration without storing cloud credentials in application properties.

- [ ] **Step 5: Route models and preserve Mock fallback**

Update `SpringAiAgentConfig` to create only enabled, fully configured provider clients. Update `ModelCatalog` to list a provider only when its client was successfully created, keep Mock available, and preserve deterministic model ordering/default selection.

- [ ] **Step 6: Verify focused and full backend tests**

```bash
cd tik-agent/server
mvn -Dtest=AgentClientContractTest,GoogleGenAiAgentClientTest,ModelCatalogTest test
mvn test
```

Expected: focused tests pass; existing tests compile against the normalized contract. No test makes an external Gemini request.

- [ ] **Step 7: Commit model adapter work**

```bash
git add tik-agent/server/pom.xml tik-agent/server/src/main/java/com/gamepub/server/agent tik-agent/server/src/test/java/com/gamepub/server/agent
git commit -m "feat: add gemini streaming model adapter"
```

## Task 3: Persist Generation Identity, Idempotency, and Epochs

**Files:**
- Create: `server/src/main/resources/db/migration/V2__create_generation_schema.sql`
- Create: `server/src/main/java/com/gamepub/server/conversation/ChatGeneration.java`
- Create: `server/src/main/java/com/gamepub/server/conversation/GenerationStatus.java`
- Create: `server/src/main/java/com/gamepub/server/conversation/GenerationMapper.java`
- Create: `server/src/main/resources/mapper/GenerationMapper.xml`
- Modify: `server/src/main/java/com/gamepub/server/conversation/Conversation.java`
- Modify: `server/src/main/java/com/gamepub/server/conversation/ConversationMapper.java`
- Modify: `server/src/main/resources/mapper/ConversationMapper.xml`
- Modify: `server/src/main/java/com/gamepub/server/conversation/Message.java`
- Modify: `server/src/main/java/com/gamepub/server/conversation/MessageMapper.java`
- Modify: `server/src/main/resources/mapper/MessageMapper.xml`
- Test: `server/src/test/java/com/gamepub/server/conversation/GenerationPersistenceTest.java`

**Interfaces:**
- Produces `GenerationMapper.create(...)`, `findByClientRequestId(...)`, `findActiveByConversationForUpdate(...)`, `transitionIfCurrent(id, epoch, expectedStatus, newStatus, ...)`, and `findStaleRunning(cutoff, limit)`.
- Produces conversation row-lock and `generation_epoch` increment operations used by generation creation.
- Generation and assistant message terminal changes must be in one MySQL transaction.

- [ ] **Step 1: Write MySQL integration tests for schema and constraints**

Using `MySqlIntegrationTest`, test V2 migration creates `chat_generations`, adds the conversation epoch and nullable message generation link, permits old messages with no generation, enforces unique `(conversation_id, client_request_id)`, and rejects a stale epoch transition.

- [ ] **Step 2: Run migration tests and verify the expected failure**

```bash
cd tik-agent/server
mvn -Dtest=GenerationPersistenceTest test
```

Expected: tests fail because V2 and generation persistence do not exist.

- [ ] **Step 3: Add forward-only V2 migration**

Create `chat_generations` with identifiers, `client_request_id`, message links, model, status, `fence_epoch`, retry source, stable error code, optional token usage, and lifecycle timestamps. Add the unique and lookup indexes from the approved technical design. Add `generation_epoch` to `conversations` and nullable `generation_id` to `messages`. Do not edit V1.

- [ ] **Step 4: Add persistence models, mapper operations, and conditional updates**

Implement the entity/enum and MyBatis mapper XML. `transitionIfCurrent` must include `id`, `fence_epoch`, and expected state in its `WHERE` clause and return affected row count. `findActiveByConversationForUpdate` must only be called in a transaction after locking the parent conversation row.

- [ ] **Step 5: Verify migration, idempotency, and stale-owner rejection**

```bash
cd tik-agent/server
mvn -Dtest=GenerationPersistenceTest test
mvn test
```

Expected: migration and conditional update tests pass. If Docker is unavailable, report the Testcontainers skip as an environment limitation, not a pass.

- [ ] **Step 6: Commit generation persistence**

```bash
git add tik-agent/server/src/main/resources/db/migration/V2__create_generation_schema.sql tik-agent/server/src/main/java/com/gamepub/server/conversation tik-agent/server/src/main/resources/mapper tik-agent/server/src/test/java/com/gamepub/server/conversation/GenerationPersistenceTest.java
git commit -m "feat: persist idempotent chat generations"
```

## Task 4: Create Transactional Generation Service

**Files:**
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationRequest.java`
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationCreation.java`
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationService.java`
- Modify: `server/src/main/java/com/gamepub/server/conversation/ConversationService.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatService.java`
- Test: `server/src/test/java/com/gamepub/server/chat/GenerationServiceTest.java`

**Interfaces:**
- `GenerationService.create(GenerationRequest request) -> GenerationCreation`
- `GenerationService.findByClientRequestId(long conversationId, String clientRequestId) -> Optional<GenerationCreation>`
- `GenerationService.startIfCreated(long generationId, long fenceEpoch) -> boolean`
- `GenerationService.markRetryable(long generationId, long fenceEpoch, String errorCode, String partialContent) -> boolean`
- `GenerationCreation` returns generation, user message, and assistant placeholder.
- Creation runs one MySQL transaction: lock conversation, recheck idempotency and activity, increment epoch, insert generation and message pair, update conversation title.

- [ ] **Step 1: Test same-key idempotency and concurrent active-task exclusion**

Add integration tests that call creation twice with the same request ID and assert the same generation/message IDs are returned; call with a different request ID while one is active and assert a stable `GENERATION_IN_PROGRESS` business error. Add a concurrency test with two threads and a barrier to verify at most one active generation is created.

- [ ] **Step 2: Run tests and verify failure**

```bash
cd tik-agent/server
mvn -Dtest=GenerationServiceTest test
```

Expected: test compilation or assertions fail because the service and request contracts are absent.

- [ ] **Step 3: Implement the transaction in the service boundary**

Use a Spring-managed transactional service. Lock the conversation row before checking active tasks. Recheck the unique idempotency key inside the transaction to cover a race after the initial lookup. Increment the database epoch and create the generation and adjacent user/assistant messages. Do not use `MAX(sequence_no)` without the parent-row lock.

- [ ] **Step 4: Make terminal updates atomic and conditional**

Implement service methods for `complete`, `fail`, `cancel`, and `retryable`. Each method updates generation and assistant message in one transaction, checks generation ID, epoch, and allowed source state, and treats zero affected rows as a stale-owner conflict.

- [ ] **Step 5: Verify concurrency and existing conversation behavior**

```bash
cd tik-agent/server
mvn -Dtest=GenerationServiceTest test
mvn test
```

Expected: generation constraints pass and existing conversation CRUD/message behavior remains intact.

- [ ] **Step 6: Commit transactional generation service**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationRequest.java tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationCreation.java tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationService.java tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationService.java tik-agent/server/src/main/java/com/gamepub/server/chat/ChatService.java tik-agent/server/src/test/java/com/gamepub/server/chat/GenerationServiceTest.java
git commit -m "feat: create generations transactionally"
```

## Task 5: Redis Generation Lease and Multi-Instance Coordination

**Files:**
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationLease.java`
- Create: `server/src/main/java/com/gamepub/server/chat/RedisGenerationLease.java`
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationLeaseService.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/GenerationRegistry.java`
- Test: `server/src/test/java/com/gamepub/server/chat/RedisGenerationLeaseTest.java`

**Interfaces:**
- `GenerationLease.acquire(long conversationId, String ownerToken, Duration ttl) -> boolean`
- `GenerationLease.renew(long conversationId, String ownerToken, Duration ttl) -> boolean`
- `GenerationLease.release(long conversationId, String ownerToken) -> boolean`
- `GenerationLeaseService` wraps acquisition/release around `GenerationService` and uses database epoch as the persisted fence.
- `GenerationRegistry` may remain as a local subscription index, but must not be the source of cross-instance exclusivity.

- [ ] **Step 1: Add lease ownership and stale-owner tests**

With Redis Testcontainers, verify one owner acquires, another owner is rejected, only the current owner can renew/release, expiration allows a new owner, and a stale release cannot delete the new owner’s lease.

- [ ] **Step 2: Run the focused test and confirm failure**

```bash
cd tik-agent/server
mvn -Dtest=RedisGenerationLeaseTest test
```

Expected: failure because the lease interface and Redis implementation do not exist.

- [ ] **Step 3: Implement atomic lease scripts**

Implement acquire with `SET NX PX` semantics. Implement renew and release with Lua scripts that compare the stored owner token before `PEXPIRE`/`DEL`. Use the conversation Redis hash tag and never use an unconditional `DEL`.

- [ ] **Step 4: Add lease orchestration and renewal**

Acquire the lease before the transactional generation creation, release on transaction failure, initialize a unique owner token per process attempt, and renew at the configured interval. If renewal fails, signal cancellation to the model subscription and attempt a conditional MySQL transition to `RETRYABLE`.

- [ ] **Step 5: Verify integration and coordination behavior**

```bash
cd tik-agent/server
mvn -Dtest=RedisGenerationLeaseTest,GenerationServiceTest test
mvn test
```

Expected: lease behavior passes against Redis; generation service remains protected by MySQL even when lease contention occurs.

- [ ] **Step 6: Commit Redis lease coordination**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationLease.java tik-agent/server/src/main/java/com/gamepub/server/chat/RedisGenerationLease.java tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationLeaseService.java tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationRegistry.java tik-agent/server/src/test/java/com/gamepub/server/chat/RedisGenerationLeaseTest.java
git commit -m "feat: coordinate generations with redis leases"
```

## Task 6: Redis Short-Term Memory and Sliding Window

**Files:**
- Create: `server/src/main/java/com/gamepub/server/memory/MemoryWindow.java`
- Create: `server/src/main/java/com/gamepub/server/memory/TokenEstimator.java`
- Create: `server/src/main/java/com/gamepub/server/memory/ConservativeTokenEstimator.java`
- Create: `server/src/main/java/com/gamepub/server/memory/MemoryWindowService.java`
- Create: `server/src/main/java/com/gamepub/server/memory/RedisShortTermMemory.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ShortTermMemory.java`
- Delete: `server/src/main/java/com/gamepub/server/chat/CaffeineShortTermMemory.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatService.java`
- Test: `server/src/test/java/com/gamepub/server/memory/MemoryWindowServiceTest.java`
- Test: `server/src/test/java/com/gamepub/server/memory/RedisShortTermMemoryTest.java`

**Interfaces:**
- `TokenEstimator.estimate(String text, String modelId) -> int`
- `MemoryWindowService.select(List<Message> history, String currentUserText, String modelId) -> MemoryWindow`
- `ShortTermMemory.load(long conversationId, String currentUserText, String modelId) -> MemoryWindow`
- Redis cache miss/version mismatch reads MySQL; Redis remains a bounded cache, not history authority.

- [ ] **Step 1: Write window-selection unit tests**

Test that newest complete user/assistant pairs are selected within both message-count and Token budgets; the system prompt and current user message are accounted for; no half-pair is included; a current message exceeding the configured input budget is rejected instead of silently truncated; and a too-large most-recent pair is skipped without selecting older pairs out of order.

- [ ] **Step 2: Run the focused test and confirm failure**

```bash
cd tik-agent/server
mvn -Dtest=MemoryWindowServiceTest test
```

Expected: failure because the window and estimator services do not exist.

- [ ] **Step 3: Implement estimator and deterministic window selection**

Add a `TokenEstimator` interface and conservative implementation with documented approximation and safety margin. Make the algorithm walk complete history pairs newest-to-oldest, stop at the first pair that cannot fit, then return the selected messages in chronological order. Count the system prompt and current message in the request budget.

- [ ] **Step 4: Add Redis short-term memory tests**

Test Redis hit, miss-to-MySQL fallback, schema/version mismatch rebuild, malformed cache rebuild, expiry configuration, and delete invalidation. Verify cached entries are immutable snapshots.

- [ ] **Step 5: Implement Redis-backed short-term memory**

Use the Redis Hash key and schema from the technical spec. Store only completed messages. Rebuild from MySQL on miss or invalid cache; write back the bounded message window and version metadata. Delete `CaffeineShortTermMemory` (and remove the Caffeine dependency if no remaining production consumer exists) so the default Spring context has exactly one `ShortTermMemory` implementation.

- [ ] **Step 6: Route prompt preparation through the window**

Update `ChatService.prepare` to load the selected memory window, add the current user input exactly once, and pass the ordered domain message list to the model client. Preserve full history in MySQL and refresh Redis only after a terminal conversation update.

- [ ] **Step 7: Verify memory and application tests**

```bash
cd tik-agent/server
mvn -Dtest=MemoryWindowServiceTest,RedisShortTermMemoryTest test
mvn test
```

Expected: all window and Redis tests pass; no long-term summary or RAG code is introduced.

- [ ] **Step 8: Commit memory implementation**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/memory tik-agent/server/src/main/java/com/gamepub/server/chat/ShortTermMemory.java tik-agent/server/src/main/java/com/gamepub/server/chat/CaffeineShortTermMemory.java tik-agent/server/src/main/java/com/gamepub/server/chat/ChatService.java tik-agent/server/src/test/java/com/gamepub/server/memory
git commit -m "feat: add redis sliding-window chat memory"
```

## Task 7: Redis Stream Store and Checkpoint Coordinator

**Files:**
- Create: `server/src/main/java/com/gamepub/server/stream/StreamContext.java`
- Create: `server/src/main/java/com/gamepub/server/stream/StreamEvent.java`
- Create: `server/src/main/java/com/gamepub/server/stream/GenerationStreamStore.java`
- Create: `server/src/main/java/com/gamepub/server/stream/RedisGenerationStreamStore.java`
- Create: `server/src/main/java/com/gamepub/server/stream/StreamCheckpointCoordinator.java`
- Test: `server/src/test/java/com/gamepub/server/stream/RedisGenerationStreamStoreTest.java`
- Test: `server/src/test/java/com/gamepub/server/stream/StreamCheckpointCoordinatorTest.java`

**Interfaces:**
- `GenerationStreamStore.append(GenerationIdentity identity, StreamEvent event, String accumulatedContent, int accumulatedTokens) -> AppendedEvent`
- `GenerationStreamStore.readAfter(GenerationIdentity identity, String streamId, int count) -> List<AppendedEvent>`
- `GenerationStreamStore.readContext(GenerationIdentity identity) -> Optional<StreamContext>`
- `GenerationStreamStore.markTerminal(GenerationIdentity identity, GenerationStatus status, String errorCode) -> boolean`
- `StreamCheckpointCoordinator.onChunk(AgentStreamChunk chunk) -> AppendedEvent`
- `GenerationIdentity` carries `conversationId`, `generationId`, `fenceEpoch`, and owner token; owner token is never serialized to a public event.
- `AppendedEvent` carries `streamId`, the normalized `StreamEvent`, and whether the operation wrote a checkpoint.

- [ ] **Step 1: Test event append, cursor ordering, and snapshot thresholds**

Write Redis tests for ordered opaque Stream IDs, append-before-send storage contract, independent `readAfter` calls returning identical event ranges, context hash round-trip, stale identity rejection, and event TTL/max-length configuration.

- [ ] **Step 2: Test Token and time checkpoint triggers**

Use a controllable `Clock`. Assert snapshot updates when estimated accumulated tokens reach the threshold, when elapsed time reaches the threshold, and unconditionally at terminal flush; assert ordinary deltas are appended even if snapshot threshold is not reached.

- [ ] **Step 3: Verify expected failures**

```bash
cd tik-agent/server
mvn -Dtest=RedisGenerationStreamStoreTest,StreamCheckpointCoordinatorTest test
```

Expected: tests fail because the stream store and checkpoint coordinator do not exist.

- [ ] **Step 4: Implement context/event models and store contract**

Define Redis-neutral Java records for context, event, identity, and appended event. Keep Redis Stream ID as `String`. Include `lastStreamId`, `lastCheckpointStreamId`, checkpoint sequence, complete content snapshot, status, and epoch in context.

- [ ] **Step 5: Implement append/checkpoint Lua script**

Use one same-slot Lua script to check active generation, owner token, epoch, and allowed state; append the event; update `lastStreamId`; update full content snapshot only when the Token/time threshold is met; and return the event ID and checkpoint result.

- [ ] **Step 6: Implement retention-safe trimming and resync metadata**

Trim only through `lastCheckpointStreamId`, ensuring the snapshot covers all removed events. Add a read result that reports the earliest retained ID and checkpoint cursor. If the snapshot cannot bridge a retention gap, return a typed unrecoverable-gap result rather than a partial replay.

- [ ] **Step 7: Implement the coordinator**

Accumulate model text and estimated tokens for the active generation, call the store for every event, perform periodic snapshot flushes by configured thresholds, and expose an explicit `flushFinalSnapshot()` for terminal paths. Do not send a browser delta unless append succeeded.

- [ ] **Step 8: Verify store and checkpoint behavior**

```bash
cd tik-agent/server
mvn -Dtest=RedisGenerationStreamStoreTest,StreamCheckpointCoordinatorTest test
mvn test
```

Expected: events replay independently, checkpoint thresholds are deterministic under a test clock, and no uncheckpointed trim gap is reported as recoverable.

- [ ] **Step 9: Commit stream store**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/stream tik-agent/server/src/test/java/com/gamepub/server/stream
git commit -m "feat: checkpoint generation streams in redis"
```

## Task 8: Integrate Generation Lifecycle and Durable Terminal Writes

**Files:**
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationRunner.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatService.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatStreamService.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatPreparation.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/SseEventFactory.java`
- Test: `server/src/test/java/com/gamepub/server/chat/GenerationRunnerTest.java`
- Test: `server/src/test/java/com/gamepub/server/chat/ChatStreamServiceTest.java`

**Interfaces:**
- `GenerationRunner.start(GenerationCreation creation, GenerationIdentity identity, MemoryWindow memory, AgentClient client) -> GenerationHandle`
- The runner consumes `Flux<AgentStreamChunk>`, writes every public event through `StreamCheckpointCoordinator`, and finishes through `GenerationService`.
- `ChatStreamService` adapts persisted events to the initial POST SSE response; it does not own generation truth in local `StringBuilder` state.
- `GenerationRunner` transitions `CREATED -> RUNNING` through `GenerationService.startIfCreated` only after Redis context initialization succeeds; if initialization or the conditional transition fails, it does not call the provider.

- [ ] **Step 1: Test success/error/cancel terminal paths**

Use a deterministic fake `AgentClient`. Verify completion writes final checkpoint then MySQL `COMPLETED`; provider error before first delta maps to stable failure; error after a delta stores partial content without automatic model retry; explicit cancellation disposes upstream and writes `CANCELLED`; stale epoch cannot finish.

- [ ] **Step 2: Test Redis/MySQL terminal failure windows**

Test MySQL success followed by Redis terminal-event failure and assert the persisted generation remains `COMPLETED`; test Redis final checkpoint failure with a live owner holding full content and assert MySQL still receives the full answer; test owner termination before MySQL commit and assert only the latest verified checkpoint is eligible for recovery.

- [ ] **Step 3: Verify expected failures**

```bash
cd tik-agent/server
mvn -Dtest=GenerationRunnerTest,ChatStreamServiceTest test
```

Expected: tests fail because the lifecycle runner and persistence-backed stream flow do not exist.

- [ ] **Step 4: Implement `GenerationRunner`**

Move model subscription, accumulated answer, status transitions, timeout handling, lease renewal signal, and terminal persistence into a focused lifecycle component. Use an atomic terminal guard so complete, cancel, timeout, and error cannot commit conflicting states.

- [ ] **Step 5: Refactor initial POST SSE adapter**

Update `ChatStreamService.open` to create/retrieve the idempotent generation, acquire/confirm ownership only for new work, initialize Redis context, start the runner once, then read events from the shared store for the response. A repeated POST with the same request ID must attach to the same generation event stream, not start another model call.

- [ ] **Step 6: Ensure status and message DTO compatibility**

Keep current `message`, `delta`, `complete`, `error`, and `cancelled` event payload fields compatible where possible; add `generationId` and use Redis Stream IDs as SSE IDs. Ensure API errors do not include raw provider exceptions.

- [ ] **Step 7: Verify backend lifecycle**

```bash
cd tik-agent/server
mvn -Dtest=GenerationRunnerTest,ChatStreamServiceTest test
mvn test
```

Expected: all state transition and failure-window tests pass; existing Mock stream still completes and writes MySQL history.

- [ ] **Step 8: Commit lifecycle integration**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat tik-agent/server/src/main/java/com/gamepub/server/stream tik-agent/server/src/test/java/com/gamepub/server/chat
git commit -m "feat: run chat generations through durable lifecycle"
```

## Task 9: Replay, Resync, Cancel, and Recovery APIs

**Files:**
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationReplayController.java`
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationReplayService.java`
- Create: `server/src/main/java/com/gamepub/server/chat/GenerationCancelController.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/StreamingMessageRecovery.java`
- Modify: `server/src/main/java/com/gamepub/server/chat/ChatController.java`
- Modify: `server/src/main/java/com/gamepub/server/common/ErrorCode.java`
- Test: `server/src/test/java/com/gamepub/server/chat/GenerationReplayIntegrationTest.java`
- Test: `server/src/test/java/com/gamepub/server/chat/StreamingMessageRecoveryTest.java`

**Interfaces:**
- `GenerationReplayService.open(long conversationId, long generationId, String lastEventId) -> SseEmitter`
- `GenerationReplayService.cancel(long conversationId, long generationId) -> void`
- Replay service uses independent `XREAD`/range reads per connection, not a shared consumer group.
- Recovery scanner transitions only stale `RUNNING` generations with expired/absent leases and validates database epoch before changing message/generation state.

- [ ] **Step 1: Test replay from an arbitrary event cursor**

Create an integration test that appends events, opens replay after a middle Stream ID, and asserts only subsequent events arrive in order. Open two readers from the same cursor and assert each receives the full same event suffix.

- [ ] **Step 2: Test retention gaps and resync**

Trim events only through a checkpoint cursor. Assert an old cursor receives a `resync` snapshot and resumes after the checkpoint. Assert a deliberately unbridgeable gap returns a typed recovery error and never claims successful replay.

- [ ] **Step 3: Test explicit cancellation and restart recovery**

Verify cancel changes state to `CANCEL_REQUESTED`, signals the current owner, and only transitions to `CANCELLED` after owner confirmation. Seed stale and live generation rows; recovery must change only stale rows whose lease is expired and whose epoch/status still match.

- [ ] **Step 4: Verify expected failures**

```bash
cd tik-agent/server
mvn -Dtest=GenerationReplayIntegrationTest,StreamingMessageRecoveryTest test
```

Expected: tests fail because replay/cancel endpoints and generation-aware recovery are absent.

- [ ] **Step 5: Implement replay and cancel endpoints**

Add `GET /api/v1/conversations/{conversationId}/generations/{generationId}/stream`, read `Last-Event-ID` as an opaque string, validate conversation/generation association, emit retained events, then block-read new entries until terminal/timeout/disconnect. Add explicit cancel POST; a disconnected replay client must not cancel the generation.

- [ ] **Step 6: Implement safe resync and terminal synthesis**

When the requested cursor is older than retained events, emit `resync` only if the snapshot cursor covers the trimmed prefix. When MySQL is terminal but Redis terminal event is missing, synthesize the final event from the persisted message. When Redis state is missing for a still-running MySQL row, transition it to `RETRYABLE`.

- [ ] **Step 7: Replace startup-only message repair with generation recovery**

Make recovery idempotent and bounded; do not mark active tasks failed solely because an application replica starts. Scan stale rows in pages, verify lease expiration, update only matching `generationId + fenceEpoch + RUNNING`, and persist the latest verified checkpoint content when available.

- [ ] **Step 8: Verify APIs, reconnect, and restart handling**

```bash
cd tik-agent/server
mvn -Dtest=GenerationReplayIntegrationTest,StreamingMessageRecoveryTest test
mvn test
```

Expected: replay, resync, cancellation, and stale recovery tests pass.

- [ ] **Step 9: Commit recovery APIs**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat tik-agent/server/src/main/java/com/gamepub/server/common/ErrorCode.java tik-agent/server/src/test/java/com/gamepub/server/chat
git commit -m "feat: add generation replay and recovery endpoints"
```

## Task 10: Frontend Idempotency, Reconnect, and Resync

**Files:**
- Modify: `ui/src/types/api.ts`
- Modify: `ui/src/api/chatStream.ts`
- Modify: `ui/src/api/sseParser.ts`
- Modify: `ui/src/hooks/useChatWorkspace.ts`
- Modify: `ui/src/components/MessageList.tsx`
- Test: `ui/src/api/chatStream.test.ts`
- Test: `ui/src/hooks/useChatWorkspace.test.tsx`

**Interfaces:**
- `streamChat` accepts `clientRequestId`, optional `generationId`, optional `lastEventId`, an `AbortSignal`, and an event callback.
- Stream IDs remain strings.
- `resync` replaces the matching assistant message content rather than appending to it.
- User stop action calls the explicit cancel endpoint; transport reconnect uses a separate abort controller and does not cancel generation.

- [ ] **Step 1: Test request idempotency and headers**

Mock `fetch`; assert the first POST body contains a UUID `clientRequestId`, retry reuses it, and replay GET sends `Last-Event-ID` exactly as an opaque string. Verify non-2xx JSON errors still surface as `ApiError`.

- [ ] **Step 2: Test reconnect and resync state transitions**

Test that a transient stream read failure reconnects using the known generation ID and last event ID; duplicate event IDs are ignored; event IDs are not parsed numerically; `resync` replaces existing partial assistant text and updates the cursor; `complete` clears generation state.

- [ ] **Step 3: Run frontend tests and confirm failure**

```bash
cd tik-agent/ui
npm test -- --run src/api/chatStream.test.ts src/hooks/useChatWorkspace.test.tsx
```

Expected: tests fail because current `streamChat` has no idempotency, replay, or resync support.

- [ ] **Step 4: Extend API types and request functions**

Add `generationId`, `clientRequestId`, and string event IDs to the TypeScript contracts. Keep current event payload typing narrow and type the `resync` snapshot explicitly. Implement separate POST-start and GET-replay request functions.

- [ ] **Step 5: Implement reconnect with bounded backoff**

Persist generation ID and last event ID in workspace state. Reconnect only for transport failures while generation remains active, use bounded exponential backoff with a maximum attempt/time budget, and reuse the same cursor. Do not retry terminal application errors.

- [ ] **Step 6: Implement idempotent reducer behavior**

Ignore duplicate/replayed events at or before the current cursor. Append new deltas only once. On `resync`, replace the assistant content with the snapshot and continue from the provided cursor. On explicit Stop, call cancel endpoint before clearing local generation state.

- [ ] **Step 7: Verify UI build and focused tests**

```bash
cd tik-agent/ui
npm test -- --run
npm run build
```

Expected: all frontend tests pass and TypeScript production build succeeds.

- [ ] **Step 8: Commit frontend recovery**

```bash
git add tik-agent/ui/src/types/api.ts tik-agent/ui/src/api/chatStream.ts tik-agent/ui/src/api/sseParser.ts tik-agent/ui/src/hooks/useChatWorkspace.ts tik-agent/ui/src/components/MessageList.tsx tik-agent/ui/src/api/chatStream.test.ts tik-agent/ui/src/hooks/useChatWorkspace.test.tsx
git commit -m "feat: resume chat streams in the client"
```

## Task 11: Operations, Observability, and End-to-End Fault Verification

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `docs/接口说明.md`
- Modify: `docs/部署说明.md`
- Modify: `docs/工作进度/二期工作.md`
- Modify: `README.md`
- Modify: `server/src/main/resources/application.yml`
- Modify: `server/src/main/java/com/gamepub/server/chat/StreamingMessageRecovery.java`
- Modify: `scripts/perf-smoke.sh`
- Test: `server/src/test/java/com/gamepub/server/chat/MultiInstanceGenerationTest.java`
- Test: `ui/src/api/sseParser.test.ts`

**Interfaces:**
- Health/readiness reports Redis unavailable without disclosing credentials.
- Metrics cover generation lifecycle, lease/checkpoint failures, replay/resync, and memory fallback without content or high-cardinality generation labels.
- Docs describe actual API/event contract and the distinction between event replay and model-stream migration.

- [ ] **Step 1: Add end-to-end acceptance cases**

Extend the existing Mock smoke path to create a generation, capture a delta ID, disconnect, reconnect with that ID, verify ordered replay and completion, then verify history from MySQL. Add duplicate POST with same request ID and assert no duplicate message pair.

- [ ] **Step 2: Add multi-instance concurrency test**

Start two application contexts sharing Testcontainers MySQL and Redis. Submit different request IDs for one conversation concurrently; assert only one active generation and one model subscription. Then reconnect from the second context and verify it reads the first context’s Redis events.

- [ ] **Step 3: Add owner-loss and Redis-failure injection tests**

Terminate or dispose the owner while a Mock stream is active; assert lease expiry and recovery transition to `RETRYABLE`, with no automatic second model invocation. Stop Redis during a generation; assert no delta is sent unless persisted to the event stream and completed MySQL history remains available.

- [ ] **Step 4: Add essential operational metrics and readiness**

Expose bounded-cardinality counters/timers for generation terminal outcomes, lease failures, checkpoint failures/latency, stream replay/resync, and memory DB fallback. Readiness must fail or report degraded state when Redis is required but unavailable. Do not include content, prompts, keys, or generation IDs in metric labels.

- [ ] **Step 5: Update Compose, smoke script, and operator documentation**

Document required env vars, Mock default, Gemini setup with a placeholder only, Redis volume/health, API payloads, event IDs, cancellation, reconnect, resync, recovery semantics, and failure diagnostics. Remove only the exposed credential line from the progress note, preserving its other user-authored changes; stage that document hunk selectively. Update progress checkboxes only for features that are actually verified. Rotate/revoke the credential outside the repository before using its replacement from a secret store.

- [ ] **Step 6: Run full backend, frontend, and Compose verification**

```bash
cd tik-agent/server
mvn test
cd ../ui
npm test -- --run
npm run build
cd ..
docker compose config
docker compose up --build -d
./scripts/perf-smoke.sh
docker compose down
```

Expected: all available tests pass; Compose starts MySQL, Redis, server, and UI; the Mock smoke path verifies incremental SSE and persisted history. Record Docker/Testcontainers limitations explicitly if the local daemon is unavailable.

- [ ] **Step 7: Review security and repository diff**

Search changed files for likely credential patterns and verify `.env` remains ignored. Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors, no accidental unrelated staging, no secret in changed files.

- [ ] **Step 8: Commit operations and acceptance**

```bash
git add tik-agent/docker-compose.yml tik-agent/.env.example tik-agent/docs/接口说明.md tik-agent/docs/部署说明.md tik-agent/docs/工作进度/二期工作.md tik-agent/README.md tik-agent/server/src/main/resources/application.yml tik-agent/server/src/main/java/com/gamepub/server/chat/StreamingMessageRecovery.java tik-agent/scripts/perf-smoke.sh tik-agent/server/src/test/java/com/gamepub/server/chat/MultiInstanceGenerationTest.java tik-agent/ui/src/api/sseParser.test.ts
git commit -m "test: verify phase two recovery and operations"
```

## Plan Self-Review

### Spec coverage

- Gemini + OpenAI-compatible + Mock provider routing: Task 2.
- Redis local infrastructure and secret-safe configuration: Tasks 1 and 11.
- MySQL generation table, idempotency, row locking, epoch fencing: Tasks 3 and 4.
- Redis lease and multi-instance coordination: Task 5.
- Token/message Sliding Window with MySQL fallback: Task 6.
- Redis Hash snapshot + Stream replay + dual checkpoint thresholds + safe trimming: Task 7.
- Generation lifecycle, terminal transactions, failure policy: Task 8.
- Last-Event-ID replay, resync, cancellation, stale recovery: Task 9.
- Frontend reconnect, duplicate suppression, resync replacement: Task 10.
- Metrics, capacity limits, multi-instance/failure tests, docs, and Compose acceptance: Task 11.
- Long-term memory/RAG and model stream migration remain excluded.

### Known implementation gates

- Confirm the exact Spring AI Google GenAI APIs and artifact available under the pinned 2.0.1 BOM before writing the adapter; keep dependency versions BOM-managed.
- Confirm the available Testcontainers Redis artifact/version from the resolved dependency tree before adding test imports.
- Keep the HTTP event DTO stable while changing the internal agent stream type.
- Preserve recovery semantics under Redis trimming: no successful `resync` may skip events not covered by its snapshot.
- Treat current workspace modifications in unrelated project directories as user work; do not include them in task commits.

### Placeholder scan

No task uses “TBD”, “TODO”, or unspecified follow-up implementation. Each task names its affected files, contracts, tests, commands, and expected result. Configuration threshold values are initial defaults from the approved technical design and remain overrideable.
