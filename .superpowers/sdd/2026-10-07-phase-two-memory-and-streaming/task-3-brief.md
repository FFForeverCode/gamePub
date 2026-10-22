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

