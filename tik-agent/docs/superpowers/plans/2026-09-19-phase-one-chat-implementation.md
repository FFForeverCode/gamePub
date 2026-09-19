# tik-agent Phase One Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a locally deployable, single-user chat application with a React UI, Spring Boot/MyBatis history storage, Spring AI Agent integration, Mock fallback, and POST-based SSE streaming.

**Architecture:** `tik-agent/server` is a modular Spring Boot monolith containing conversation, chat, persistence, and Spring AI Agent modules. `tik-agent/ui` is a React/Vite client that uses JSON REST APIs for history and `fetch` stream parsing for SSE; Docker Compose runs UI, server, and MySQL as one local stack.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1, Spring MVC, Reactor, MyBatis Starter 4.0.1, Flyway, MySQL 8.4, Caffeine, JUnit 5, Testcontainers 2.0.5, React 19.1, Vite 7.1, TypeScript 5.8, Vitest 3, Testing Library, Lucide React, Nginx, Docker Compose.

**Spec:** `tik-agent/docs/superpowers/specs/2026-09-19-phase-one-chat-design.md`

## Global Constraints

- Backend and all Spring AI Agent code live under `tik-agent/server`; do not create another backend or Agent service.
- Frontend code lives under `tik-agent/ui`; do not create a `web` project.
- The application is single-user and has no authentication in phase one.
- Agent behavior uses Spring AI `ChatClient`, `ChatModel`, and Spring AI message types; do not introduce another Agent framework.
- Mock chat works without credentials; OpenAI-compatible models are listed only when configured through environment variables.
- MySQL history is durable; Caffeine is only a rebuildable short-term cache of the latest 20 completed messages.
- Streaming uses `POST` plus `Accept: text/event-stream`; the browser uses `fetch`, a streaming parser, and `AbortController`.
- One conversation can have only one active generation; concurrent submission returns `409`.
- Do not persist API keys, full prompts, or full model answers in logs.
- Phase one does not include accounts, long-term memory, RAG, tools, multi-Agent orchestration, Redis, Elasticsearch, or Kafka.

---

## File Structure

### Backend ownership

```text
tik-agent/server/
  pom.xml                                      dependency and build versions
  Dockerfile                                   production server image
  src/main/java/com/gamepub/server/
    ServerApplication.java                     application entry and properties scanning
    common/
      ApiError.java                            stable JSON error body
      BusinessException.java                   typed pre-stream business failures
      ErrorCode.java                           HTTP/SSE error code catalog
      GlobalExceptionHandler.java              JSON exception mapping
      RequestIdFilter.java                     request correlation header/MDC value
    config/
      TikAgentProperties.java                  typed tik-agent configuration
      AsyncConfig.java                         bounded stream executor
    conversation/
      Conversation.java                        persistence entity
      Message.java                             persistence entity
      MessagePair.java                         persisted user/assistant pair
      MessageRole.java                         USER/ASSISTANT enum
      MessageStatus.java                       STREAMING/COMPLETED/FAILED/CANCELLED enum
      ConversationDeletedEvent.java            cache invalidation event
      ConversationMapper.java                  conversation SQL contract
      MessageMapper.java                       message SQL contract
      ConversationService.java                 transactional history behavior
      ConversationController.java              conversation REST API
      ConversationDtos.java                    request/response records
    agent/
      AgentMessage.java                        provider-neutral input record
      AgentClient.java                         streaming Agent boundary
      ModelDescriptor.java                     public model metadata
      ModelCatalog.java                        configured-model lookup
      MockAgentClient.java                     deterministic local stream
      SpringAiAgentClient.java                 Spring AI ChatClient adapter
      SpringAiAgentConfig.java                 OpenAI-compatible ChatModel/ChatClient registration
      ModelController.java                     GET /api/v1/models
    chat/
      ChatCommand.java                         validated stream command
      ChatPreparation.java                     persisted user/assistant pair
      ShortTermMemory.java                     context cache boundary
      CaffeineShortTermMemory.java             Caffeine-backed implementation
      GenerationRegistry.java                  one-active-stream invariant
      ChatService.java                         transaction and lifecycle operations
      ChatStreamService.java                   Agent-to-SseEmitter orchestration
      ChatController.java                      POST SSE endpoint
      SseEventFactory.java                     stable event payload creation
      StreamingMessageRecovery.java            startup repair for abandoned streams
  src/main/resources/
    application.yml                            defaults and environment bindings
    db/migration/V1__create_chat_schema.sql    MySQL schema
    mapper/ConversationMapper.xml              conversation SQL
    mapper/MessageMapper.xml                   message SQL
  src/test/java/com/gamepub/server/
    support/MySqlIntegrationTest.java          shared MySQL Testcontainer
    conversation/ConversationMapperTest.java
    conversation/ConversationServiceTest.java
    conversation/ConversationControllerTest.java
    agent/ModelCatalogTest.java
    agent/SpringAiAgentClientTest.java
    chat/CaffeineShortTermMemoryTest.java
    chat/GenerationRegistryTest.java
    chat/ChatStreamServiceTest.java
    chat/StreamingMessageRecoveryTest.java
```

### Frontend ownership

```text
tik-agent/ui/
  package.json                                  scripts and dependencies
  vite.config.ts                               dev proxy and test setup
  tsconfig*.json                               strict TypeScript configuration
  index.html                                   application shell
  Dockerfile                                   production UI image
  nginx.conf                                   SPA fallback and unbuffered SSE proxy
  src/
    main.tsx                                   React bootstrap
    App.tsx                                    chat workspace composition
    types/api.ts                               API and SSE discriminated unions
    api/http.ts                                JSON request/error handling
    api/conversations.ts                       conversation/model REST calls
    api/sseParser.ts                           byte-safe SSE frame parser
    api/chatStream.ts                          POST stream and abort support
    hooks/useChatWorkspace.ts                  conversation and generation state
    components/AppHeader.tsx                   model selection and mobile menu
    components/ConversationSidebar.tsx         conversation actions
    components/MessageList.tsx                 history and stream rendering
    components/ChatComposer.tsx                send/stop input control
    components/EmptyState.tsx                  first-use state
    styles/global.css                          tokens, layout, responsive states
    test/setup.ts                              Testing Library setup
    api/sseParser.test.ts
    api/chatStream.test.ts
    hooks/useChatWorkspace.test.tsx
    components/ChatWorkspace.test.tsx
```

### Deployment and documentation ownership

```text
tik-agent/
  docker-compose.yml                           ui/server/mysql local stack
  .env.example                                 non-secret configuration template
  scripts/perf-smoke.sh                        repeatable REST/SSE baseline
  docs/部署说明.md                              build, run, configure, troubleshoot
  docs/接口说明.md                              REST and SSE examples
  docs/工作进度/一期工作.md                     delivered scope and verification record
```

---

### Task 1: Establish the backend runtime, schema, and MySQL test harness

**Files:**
- Modify: `tik-agent/server/pom.xml`
- Modify: `tik-agent/server/src/main/java/com/gamepub/server/ServerApplication.java`
- Modify: `tik-agent/server/src/main/resources/application.yml`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/config/TikAgentProperties.java`
- Create: `tik-agent/server/src/main/resources/db/migration/V1__create_chat_schema.sql`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/support/MySqlIntegrationTest.java`
- Modify: `tik-agent/server/src/test/java/com/gamepub/server/ServerApplicationTests.java`

**Interfaces:**
- Consumes: Existing Spring Boot application skeleton.
- Produces: `TikAgentProperties`, Flyway-managed `conversations` and `messages` tables, and `MySqlIntegrationTest` for all persistence/API integration tests.

- [ ] **Step 1: Write the failing schema smoke test**

Use a shared MySQL container and assert that Flyway creates both tables:

```java
@SpringBootTest
class ServerApplicationTests extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndMigratesChatTables() {
        Integer count = jdbcTemplate.queryForObject("""
            select count(*) from information_schema.tables
            where table_schema = database()
              and table_name in ('conversations', 'messages')
            """, Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
```

`MySqlIntegrationTest` must use `MySQLContainer("mysql:8.4")` and `@DynamicPropertySource` to bind `spring.datasource.url`, `username`, and `password`.

- [ ] **Step 2: Run the smoke test and verify the missing dependencies/schema fail**

Run: `cd tik-agent/server && mvn -Dtest=ServerApplicationTests test`

Expected: FAIL because JDBC, Testcontainers, and the migration are not configured.

- [ ] **Step 3: Add the backend dependency baseline**

Keep Spring Boot `4.1.1` and Java `17`; import Spring AI BOM `2.0.1`. Add these dependencies:

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
<dependency><groupId>org.mybatis.spring.boot</groupId><artifactId>mybatis-spring-boot-starter</artifactId><version>4.0.1</version></dependency>
<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-model-openai</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
<dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
<dependency><groupId>com.github.ben-manes.caffeine</groupId><artifactId>caffeine</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-mysql</artifactId><scope>test</scope></dependency>
```

Use the Spring Boot managed Testcontainers `2.0.5`; do not override it.

- [ ] **Step 4: Add typed application configuration and the V1 migration**

Bind this configuration shape with `@ConfigurationProperties("tik-agent")`:

```java
public record TikAgentProperties(
    Chat chat,
    Memory memory,
    Agent agent
) {
    public record Chat(int maxContentLength, Duration streamTimeout) {}
    public record Memory(int messageLimit, Duration expireAfterAccess, long maximumSize) {}
    public record Agent(String defaultModel, String systemPrompt, Map<String, Model> models) {}
    public record Model(String displayName, String provider, String baseUrl,
                        String apiKey, String modelName, double temperature,
                        Duration timeout, boolean enabled) {}
}
```

Configure defaults: max content `10000`, stream timeout `120s`, memory limit `20`, expiry `30m`, maximum `1000`, default model `mock`, and a concise Chinese assistant system prompt. Disable Spring AI's automatic OpenAI model creation so missing credentials do not break Mock-only startup; `SpringAiAgentConfig` will create configured clients explicitly in Task 4.

Create both tables exactly as defined in the spec, using `BIGINT AUTO_INCREMENT`, millisecond timestamps, `ON DELETE CASCADE`, unique `(conversation_id, sequence_no)`, and indexes on conversation update time and message conversation ID.

- [ ] **Step 5: Run the smoke test and package build**

Run: `cd tik-agent/server && mvn -Dtest=ServerApplicationTests test`

Expected: PASS; Flyway reports migration version `1`.

Run: `cd tik-agent/server && mvn -DskipTests package`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit the runtime baseline**

```bash
git add tik-agent/server/pom.xml tik-agent/server/src
git commit -m "build: configure chat server runtime"
```

---

### Task 2: Implement MyBatis entities, mappers, and transactional conversation behavior

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/Conversation.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/Message.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/MessagePair.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/MessageRole.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/MessageStatus.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationDeletedEvent.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationMapper.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/MessageMapper.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationService.java`
- Create: `tik-agent/server/src/main/resources/mapper/ConversationMapper.xml`
- Create: `tik-agent/server/src/main/resources/mapper/MessageMapper.xml`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/conversation/ConversationMapperTest.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/conversation/ConversationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 schema and MySQL integration base.
- Produces: `ConversationService.create/list/rename/delete/messages`, `MessageMapper.findRecentCompleted`, and transaction-safe `MessagePair` creation used by chat streaming.

- [ ] **Step 1: Write failing mapper tests for ordering, cursor paging, message order, and cascade delete**

Include a test that updates an older conversation and proves ordering uses `(updated_at DESC, id DESC)`. The cursor mapper accepts the anchor conversation ID and compares against that row's `(updated_at, id)` tuple:

```java
List<Conversation> first = conversationMapper.findPage(null, 2);
List<Conversation> second = conversationMapper.findPage(first.get(first.size() - 1).id(), 2);
assertThat(first).extracting(Conversation::title).containsExactly("updated-old", "newest");
assertThat(second).extracting(Conversation::title).doesNotContain("updated-old", "newest");

conversationMapper.deleteById(conversationId);
assertThat(messageMapper.findByConversationId(conversationId)).isEmpty();
```

- [ ] **Step 2: Run mapper tests and verify they fail**

Run: `cd tik-agent/server && mvn -Dtest=ConversationMapperTest test`

Expected: FAIL because entities, mapper interfaces, and XML statements do not exist.

- [ ] **Step 3: Implement focused entities and mapper contracts**

Use immutable records for reads and explicit mapper parameters:

```java
public record Conversation(Long id, String title, Instant createdAt, Instant updatedAt) {}

public record Message(Long id, Long conversationId, MessageRole role, String content,
                      MessageStatus status, String modelId, long sequenceNo,
                      String errorCode, Instant createdAt, Instant updatedAt) {}

public record MessagePair(Message userMessage, Message assistantMessage) {}
public record ConversationDeletedEvent(long conversationId) {}

public interface ConversationMapper {
    int insert(Conversation conversation);
    Conversation findById(long id);
    List<Conversation> findPage(@Param("beforeId") Long beforeId, @Param("limit") int limit);
    int rename(@Param("id") long id, @Param("title") String title);
    int touch(@Param("id") long id);
    int deleteById(long id);
}

public interface MessageMapper {
    int insert(Message message);
    Message findById(long id);
    long findMaxSequenceNo(long conversationId);
    List<Message> findByConversationId(long conversationId);
    List<Message> findRecentCompleted(@Param("conversationId") long conversationId,
                                      @Param("limit") int limit);
    int finish(@Param("id") long id, @Param("content") String content,
               @Param("status") MessageStatus status, @Param("errorCode") String errorCode);
    int failStaleStreaming(@Param("cutoff") Instant cutoff,
                           @Param("errorCode") String errorCode);
}
```

Configure MyBatis underscore-to-camel mapping and enum string handling. `findRecentCompleted` fetches the newest N rows in a subquery and returns them ascending by sequence.

- [ ] **Step 4: Run mapper tests and verify they pass**

Run: `cd tik-agent/server && mvn -Dtest=ConversationMapperTest test`

Expected: PASS.

- [ ] **Step 5: Write failing service tests for title rules and transaction behavior**

Cover default title `新对话`, trimmed rename, a 120-character title limit, not-found failures, and first-question title generation:

```java
assertThat(ConversationService.titleFromFirstMessage("  这是第一条非常清楚的问题  "))
    .isEqualTo("这是第一条非常清楚的问题");
assertThat(ConversationService.titleFromFirstMessage("a".repeat(40)))
    .hasSize(30);
```

Verify `createMessagePair(conversationId, content, modelId)` inserts a `COMPLETED` user message followed by a `STREAMING` assistant message with adjacent sequence numbers and touches the conversation in one transaction.

- [ ] **Step 6: Implement `ConversationService` minimally and run its tests**

Publish `ConversationDeletedEvent` only after a successful database delete. Expose these exact methods:

```java
Conversation create();
List<Conversation> list(Long beforeId, int limit);
Conversation rename(long id, String title);
void delete(long id);
List<Message> messages(long conversationId);
MessagePair createMessagePair(long conversationId, String content, String modelId);
void completeAssistant(long messageId, String content);
void failAssistant(long messageId, String content, String errorCode);
void cancelAssistant(long messageId, String content);
```

Run: `cd tik-agent/server && mvn -Dtest=ConversationServiceTest test`

Expected: PASS with rollback verified when the second insert is forced to fail.

- [ ] **Step 7: Commit persistence behavior**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/conversation tik-agent/server/src/main/resources/mapper tik-agent/server/src/test/java/com/gamepub/server/conversation
git commit -m "feat: persist conversations and messages"
```

---

### Task 3: Expose conversation REST APIs and stable JSON errors

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/common/ApiError.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/common/BusinessException.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/common/ErrorCode.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/common/GlobalExceptionHandler.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/common/RequestIdFilter.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationDtos.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/conversation/ConversationController.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/conversation/ConversationControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `ConversationService`.
- Produces: The five `/api/v1/conversations` endpoints and `ApiError(code, message, requestId)` used by UI HTTP handling.

- [ ] **Step 1: Write failing MockMvc API contract tests**

Verify exact status codes and JSON fields:

```java
mockMvc.perform(post("/api/v1/conversations"))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.title").value("新对话"));

mockMvc.perform(patch("/api/v1/conversations/{id}", id)
        .contentType(APPLICATION_JSON)
        .content("{\"title\":\"  新标题  \"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.title").value("新标题"));

mockMvc.perform(get("/api/v1/conversations/999/messages"))
    .andExpect(status().isNotFound())
    .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"))
    .andExpect(jsonPath("$.requestId").isNotEmpty());
```

Also test `limit=0`, `limit=101`, blank title, list cursor, and `204` deletion.

- [ ] **Step 2: Run the controller test and verify it fails**

Run: `cd tik-agent/server && mvn -Dtest=ConversationControllerTest test`

Expected: FAIL with unmapped endpoints.

- [ ] **Step 3: Implement the API DTO and error contracts**

Use records and Bean Validation:

```java
record RenameConversationRequest(@NotBlank @Size(max = 120) String title) {}
record ConversationResponse(long id, String title, Instant createdAt, Instant updatedAt) {}
record MessageResponse(long id, long conversationId, String role, String content,
                       String status, String modelId, long sequenceNo,
                       String errorCode, Instant createdAt, Instant updatedAt) {}
public record ApiError(String code, String message, String requestId) {}
```

Map `CONVERSATION_NOT_FOUND` to `404`, validation to `400`, and unexpected errors to `500` without leaking stack traces. Echo or generate `X-Request-Id`, include it in response headers and `ApiError`, and remove it from MDC in a `finally` block.

- [ ] **Step 4: Implement the controller and verify all endpoint tests pass**

Run: `cd tik-agent/server && mvn -Dtest=ConversationControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit the conversation API**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/common tik-agent/server/src/main/java/com/gamepub/server/conversation tik-agent/server/src/test/java/com/gamepub/server/conversation
git commit -m "feat: expose conversation history api"
```

---

### Task 4: Implement the Spring AI Agent boundary, model catalog, and Mock fallback

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/AgentMessage.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/AgentClient.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/ModelDescriptor.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/ModelCatalog.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/MockAgentClient.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/SpringAiAgentClient.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/SpringAiAgentConfig.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/agent/ModelController.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/agent/ModelCatalogTest.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/agent/SpringAiAgentClientTest.java`

**Interfaces:**
- Consumes: Task 1 `TikAgentProperties`.
- Produces: `AgentClient.stream(List<AgentMessage>)`, `ModelCatalog.requireClient(modelId)`, and `GET /api/v1/models`.

- [ ] **Step 1: Write failing model catalog tests**

Cover Mock-only startup, disabled/unconfigured real models, default selection, stable ordering, and unknown model errors:

```java
assertThat(catalog.availableModels())
    .extracting(ModelDescriptor::id)
    .containsExactly("mock");
assertThatThrownBy(() -> catalog.requireClient("missing"))
    .isInstanceOf(BusinessException.class)
    .extracting("errorCode").isEqualTo(ErrorCode.MODEL_NOT_AVAILABLE);
```

- [ ] **Step 2: Write a failing Spring AI message mapping test**

Mock a `ChatClient` request chain and verify domain messages become Spring AI messages in this order: configured `SystemMessage`, historical `UserMessage`, historical `AssistantMessage`, current `UserMessage`. Assert `stream().content()` is returned unchanged.

```java
StepVerifier.create(client.stream(List.of(
        new AgentMessage(MessageRole.USER, "问题"),
        new AgentMessage(MessageRole.ASSISTANT, "回答"))))
    .expectNext("分片一", "分片二")
    .verifyComplete();
```

- [ ] **Step 3: Run both Agent tests and verify they fail**

Run: `cd tik-agent/server && mvn -Dtest=ModelCatalogTest,SpringAiAgentClientTest test`

Expected: FAIL because Agent contracts do not exist.

- [ ] **Step 4: Implement the Agent contracts and deterministic Mock stream**

Use these exact contracts:

```java
public record AgentMessage(MessageRole role, String content) {}

public interface AgentClient {
    Flux<String> stream(List<AgentMessage> messages);
}

public record ModelDescriptor(String id, String displayName, String provider,
                              boolean defaultModel) {}
```

`MockAgentClient` derives a deterministic Chinese reply from the final user message and emits 3-8 character chunks every 35 ms using Reactor; no blocking sleep.

- [ ] **Step 5: Implement Spring AI client registration**

For each enabled, nonblank OpenAI-compatible model configuration, build `OpenAiChatOptions` with `baseUrl`, `apiKey`, `model`, `temperature`, and `timeout`, then build `OpenAiChatModel`, wrap it with `ChatClient.create(chatModel)`, and register `SpringAiAgentClient`. The adapter calls:

```java
return chatClient.prompt()
    .messages(springMessages)
    .stream()
    .content();
```

The system prompt is the first Spring AI message. Do not log message content or credentials.

- [ ] **Step 6: Add and test the model list endpoint**

`GET /api/v1/models` returns `List<ModelDescriptor>` and always includes Mock. Run:

`cd tik-agent/server && mvn -Dtest=ModelCatalogTest,SpringAiAgentClientTest test`

Expected: PASS.

- [ ] **Step 7: Commit the Spring AI Agent layer**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/agent tik-agent/server/src/test/java/com/gamepub/server/agent
git commit -m "feat: add Spring AI agent model catalog"
```

---

### Task 5: Implement short-term memory and generation mutual exclusion

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ShortTermMemory.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/CaffeineShortTermMemory.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/GenerationRegistry.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/chat/CaffeineShortTermMemoryTest.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/chat/GenerationRegistryTest.java`

**Interfaces:**
- Consumes: Task 2 `MessageMapper.findRecentCompleted` and Task 4 `AgentMessage`.
- Produces: Rebuildable latest-N context and per-conversation `Disposable` lifecycle management.

- [ ] **Step 1: Write failing memory tests**

Prove cache miss loads once, results remain chronological, only completed messages are mapped, append trims to 20, and invalidate causes reload. Publish `ConversationDeletedEvent` and assert the next read reloads from MySQL:

```java
assertThat(memory.get(conversationId))
    .extracting(AgentMessage::content)
    .containsExactly("u1", "a1");
assertThat(memory.get(conversationId)).hasSize(2);
verify(messageMapper, times(1)).findRecentCompleted(conversationId, 20);

memory.invalidate(conversationId);
memory.get(conversationId);
verify(messageMapper, times(2)).findRecentCompleted(conversationId, 20);
```

- [ ] **Step 2: Write failing generation registry tests**

Verify the second reservation fails, cancellation disposes once, and stale completion cannot remove a newer reservation. Use an opaque generation token:

```java
GenerationRegistry.Lease first = registry.reserve(42L);
assertThatThrownBy(() -> registry.reserve(42L))
    .isInstanceOf(BusinessException.class)
    .extracting("errorCode").isEqualTo(ErrorCode.GENERATION_IN_PROGRESS);
first.attach(disposable);
first.cancel();
verify(disposable).dispose();
```

- [ ] **Step 3: Run tests and verify they fail**

Run: `cd tik-agent/server && mvn -Dtest=CaffeineShortTermMemoryTest,GenerationRegistryTest test`

Expected: FAIL because cache and registry do not exist.

- [ ] **Step 4: Implement the cache and lease API**

Use these contracts:

```java
public interface ShortTermMemory {
    List<AgentMessage> get(long conversationId);
    void appendCompleted(long conversationId, List<AgentMessage> messages);
    void invalidate(long conversationId);
}

public final class GenerationRegistry {
    public Lease reserve(long conversationId);
    public boolean isActive(long conversationId);
    public final class Lease {
        public UUID id();
        public void attach(Disposable disposable);
        public void complete();
        public void cancel();
    }
}
```

Build Caffeine from the typed memory properties. Return immutable list snapshots so callers cannot mutate cached state. Add a `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` for `ConversationDeletedEvent` that calls `invalidate(event.conversationId())`.

- [ ] **Step 5: Run both tests and commit**

Run: `cd tik-agent/server && mvn -Dtest=CaffeineShortTermMemoryTest,GenerationRegistryTest test`

Expected: PASS.

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat tik-agent/server/src/test/java/com/gamepub/server/chat
git commit -m "feat: manage chat context and active generations"
```

---

### Task 6: Implement transactional chat lifecycle and SSE orchestration

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ChatCommand.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ChatPreparation.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ChatService.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/SseEventFactory.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ChatStreamService.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/ChatController.java`
- Create: `tik-agent/server/src/main/java/com/gamepub/server/config/AsyncConfig.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/chat/ChatStreamServiceTest.java`

**Interfaces:**
- Consumes: Conversation persistence, Agent catalog, memory, and generation leases from Tasks 2-5.
- Produces: `POST /api/v1/conversations/{id}/messages/stream` with `message`, `delta`, `complete`, `error`, and `cancelled` events.

- [ ] **Step 1: Write failing successful-stream test**

Use a recording `SseEmitter` test double and deterministic `Flux.just("你", "好")`. Assert event order and persistence lifecycle:

```java
assertThat(events).extracting(RecordedEvent::name)
    .containsExactly("message", "delta", "delta", "complete");
verify(conversationService).completeAssistant(assistantId, "你好");
verify(memory).appendCompleted(conversationId, List.of(
    new AgentMessage(USER, "问题"), new AgentMessage(ASSISTANT, "你好")));
assertThat(registry.isActive(conversationId)).isFalse();
```

Assert every event has an increasing numeric SSE ID.

- [ ] **Step 2: Write failing error, conflict, overflow, and disconnect tests**

Cover:

- Invalid blank/oversized content fails before an emitter is returned with `400`.
- Unknown model fails before streaming with `503`.
- Existing generation fails with `409` and writes no messages.
- Upstream rate-limit/timeout maps to `MODEL_RATE_LIMITED`/`MODEL_TIMEOUT`, persists partial text as `FAILED`, then emits `error`.
- Output beyond 1 MiB cancels upstream and persists `FAILED` with `RESPONSE_TOO_LARGE`.
- `IOException` while sending a delta disposes upstream and persists partial text as `CANCELLED`.
- Failure and cancellation append only the completed user message to hot memory; the failed/cancelled assistant is excluded, matching a future database cache rebuild.

```java
when(agent.stream(any())).thenReturn(Flux.concat(
    Flux.just("partial"), Flux.error(new TimeoutException())));
streamService.stream(command, emitter);
await().untilAsserted(() -> verify(conversationService)
    .failAssistant(assistantId, "partial", "MODEL_TIMEOUT"));
```

- [ ] **Step 3: Run the stream tests and verify they fail**

Run: `cd tik-agent/server && mvn -Dtest=ChatStreamServiceTest test`

Expected: FAIL because lifecycle and SSE services do not exist.

- [ ] **Step 4: Implement synchronous validation/preparation and asynchronous streaming**

Use:

```java
public record ChatCommand(long conversationId, String content, String modelId) {}
public record ChatPreparation(Message userMessage, Message assistantMessage,
                              List<AgentMessage> context) {}

public ChatPreparation prepare(ChatCommand command) {
    List<AgentMessage> history = memory.get(command.conversationId());
    MessagePair pair = conversationService.createMessagePair(
        command.conversationId(), command.content(), command.modelId());
    List<AgentMessage> context = new ArrayList<>(history);
    context.add(new AgentMessage(MessageRole.USER, command.content()));
    return new ChatPreparation(pair.userMessage(), pair.assistantMessage(), List.copyOf(context));
}

public SseEmitter openStream(ChatCommand command) {
    // Validate and reserve synchronously so errors still use JSON HTTP responses.
    GenerationRegistry.Lease lease = registry.reserve(command.conversationId());
    try {
        ChatPreparation prepared = chatService.prepare(command);
        SseEmitter emitter = new SseEmitter(properties.chat().streamTimeout().toMillis());
        executor.execute(() -> subscribe(prepared, command, lease, emitter));
        return emitter;
    } catch (RuntimeException ex) {
        lease.complete();
        throw ex;
    }
}
```

Build Agent input from cached completed history plus the current user message exactly once. Use one bounded `ThreadPoolTaskExecutor` for emitter writes; never block the MVC request thread waiting for model completion.

- [ ] **Step 5: Implement SSE payload records and terminal-state idempotency**

Use an `AtomicBoolean terminal` so completion, timeout, disconnect, and upstream error cannot persist conflicting statuses. Payloads must match the spec:

```java
record MessageStarted(MessageResponse userMessage, MessageResponse assistantMessage) {}
record Delta(long messageId, String content) {}
record Completed(MessageResponse message) {}
record StreamError(long messageId, String code, String message) {}
record Cancelled(MessageResponse message) {}
```

Set response type to `text/event-stream`, send a 15-second comment heartbeat while no content arrives, and stop heartbeat scheduling at every terminal path.

- [ ] **Step 6: Implement the controller contract**

```java
record SendMessageRequest(
    @NotBlank @Size(max = 10000) String content,
    @NotBlank String modelId
) {}

@PostMapping(path = "/api/v1/conversations/{id}/messages/stream",
             produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter stream(@PathVariable long id, @Valid @RequestBody SendMessageRequest request)
```

- [ ] **Step 7: Run focused and full backend tests**

Run: `cd tik-agent/server && mvn -Dtest=ChatStreamServiceTest test`

Expected: PASS.

Run: `cd tik-agent/server && mvn test`

Expected: PASS with no active stream thread left after test completion.

- [ ] **Step 8: Commit streaming chat**

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat tik-agent/server/src/main/java/com/gamepub/server/config tik-agent/server/src/test/java/com/gamepub/server/chat
git commit -m "feat: stream agent responses over SSE"
```

---

### Task 7: Repair interrupted generations at startup

**Files:**
- Create: `tik-agent/server/src/main/java/com/gamepub/server/chat/StreamingMessageRecovery.java`
- Create: `tik-agent/server/src/test/java/com/gamepub/server/chat/StreamingMessageRecoveryTest.java`

**Interfaces:**
- Consumes: Task 2 `MessageMapper.failStaleStreaming` and configured stream timeout.
- Produces: Startup transition from stale `STREAMING` to `FAILED/GENERATION_INTERRUPTED`.

- [ ] **Step 1: Write the failing recovery test**

Seed one old streaming message, one recent streaming message, and one completed message. Invoke recovery and assert only the old row changes:

```java
recovery.repairInterruptedMessages();
assertThat(messageMapper.findById(oldId).status()).isEqualTo(FAILED);
assertThat(messageMapper.findById(oldId).errorCode()).isEqualTo("GENERATION_INTERRUPTED");
assertThat(messageMapper.findById(recentId).status()).isEqualTo(STREAMING);
```

- [ ] **Step 2: Run the test and verify failure**

Run: `cd tik-agent/server && mvn -Dtest=StreamingMessageRecoveryTest test`

Expected: FAIL because startup recovery is absent.

- [ ] **Step 3: Implement recovery after application readiness**

Listen for `ApplicationReadyEvent`, calculate `cutoff = clock.instant().minus(streamTimeout)`, update stale rows in one transaction, and log only the repaired row count.

- [ ] **Step 4: Run recovery and full backend tests, then commit**

Run: `cd tik-agent/server && mvn -Dtest=StreamingMessageRecoveryTest test`

Expected: PASS.

Run: `cd tik-agent/server && mvn test`

Expected: PASS.

```bash
git add tik-agent/server/src/main/java/com/gamepub/server/chat/StreamingMessageRecovery.java tik-agent/server/src/test/java/com/gamepub/server/chat/StreamingMessageRecoveryTest.java
git commit -m "feat: recover interrupted chat generations"
```

---

### Task 8: Scaffold the React UI and implement typed HTTP/SSE clients

**Files:**
- Create: `tik-agent/ui/package.json`
- Create: `tik-agent/ui/package-lock.json`
- Create: `tik-agent/ui/vite.config.ts`
- Create: `tik-agent/ui/tsconfig.json`
- Create: `tik-agent/ui/tsconfig.app.json`
- Create: `tik-agent/ui/tsconfig.node.json`
- Create: `tik-agent/ui/index.html`
- Create: `tik-agent/ui/src/main.tsx`
- Create: `tik-agent/ui/src/types/api.ts`
- Create: `tik-agent/ui/src/api/http.ts`
- Create: `tik-agent/ui/src/api/conversations.ts`
- Create: `tik-agent/ui/src/api/sseParser.ts`
- Create: `tik-agent/ui/src/api/chatStream.ts`
- Create: `tik-agent/ui/src/test/setup.ts`
- Create: `tik-agent/ui/src/api/sseParser.test.ts`
- Create: `tik-agent/ui/src/api/chatStream.test.ts`

**Interfaces:**
- Consumes: REST and SSE contracts from Tasks 3, 4, and 6.
- Produces: Strict API types, JSON request functions, `parseSseStream`, and abortable `streamMessage` used by UI state.

- [ ] **Step 1: Create package metadata and install locked dependencies**

Use scripts `dev`, `build`, `test`, and `test:run`. Runtime dependencies: `react`, `react-dom`, `lucide-react`. Dev dependencies: Vite React plugin, TypeScript, Vitest, jsdom, Testing Library, and type packages.

Run: `cd tik-agent/ui && npm install`

Expected: `package-lock.json` is created and `npm audit` output is recorded; do not use `--force` to rewrite dependency ranges.

- [ ] **Step 2: Write failing SSE parser tests**

Test arbitrary byte boundaries, CRLF, comments, multiple `data:` lines, unknown events, final frame without a trailing blank line, and malformed JSON:

```ts
const chunks = [encode('event: del'), encode('ta\r\ndata: {"messageId":2,'),
  encode('"content":"你"}\r\n\r\n')]
const events = await collect(parseSseStream(streamFrom(chunks)))
expect(events).toEqual([{ type: 'delta', messageId: 2, content: '你' }])
```

Malformed known-event JSON must throw `SseParseError` with the event name and frame index; comment heartbeats produce no application event.

- [ ] **Step 3: Run parser tests and verify failure**

Run: `cd tik-agent/ui && npm run test:run -- src/api/sseParser.test.ts`

Expected: FAIL because the parser is missing.

- [ ] **Step 4: Implement API types and the incremental parser**

Define discriminated unions:

```ts
export type StreamEvent =
  | { type: 'message'; userMessage: Message; assistantMessage: Message }
  | { type: 'delta'; messageId: number; content: string }
  | { type: 'complete'; message: Message }
  | { type: 'error'; messageId: number; code: string; message: string }
  | { type: 'cancelled'; message: Message }

export async function* parseSseStream(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<StreamEvent>
```

Use one streaming `TextDecoder`, normalize CRLF only after decoding, accumulate incomplete frames, join multiple data lines with `\n`, and dispatch only the five known event names.

- [ ] **Step 5: Write failing HTTP and abort tests**

Mock `fetch` and verify JSON errors become `ApiClientError`, stream requests use POST/headers/body correctly, events are forwarded in order, and abort is not surfaced as a user-facing error:

```ts
await streamMessage(7, { content: '问题', modelId: 'mock' }, signal, onEvent)
expect(fetch).toHaveBeenCalledWith('/api/v1/conversations/7/messages/stream',
  expect.objectContaining({ method: 'POST', signal }))
```

- [ ] **Step 6: Implement REST functions and `streamMessage`**

Expose:

```ts
listModels(): Promise<ModelDescriptor[]>
listConversations(beforeId?: number): Promise<Conversation[]>
createConversation(): Promise<Conversation>
renameConversation(id: number, title: string): Promise<Conversation>
deleteConversation(id: number): Promise<void>
listMessages(id: number): Promise<Message[]>
streamMessage(id: number, request: SendMessageRequest,
              signal: AbortSignal,
              onEvent: (event: StreamEvent) => void): Promise<void>
```

Reject a missing response body and non-`text/event-stream` success response as protocol errors.

- [ ] **Step 7: Run UI client tests and build, then commit**

Run: `cd tik-agent/ui && npm run test:run -- src/api`

Expected: PASS.

Run: `cd tik-agent/ui && npm run build`

Expected: PASS with no TypeScript errors.

```bash
git add tik-agent/ui
git commit -m "feat: add typed chat api client"
```

---

### Task 9: Implement the chat workspace state hook

**Files:**
- Create: `tik-agent/ui/src/hooks/useChatWorkspace.ts`
- Create: `tik-agent/ui/src/hooks/useChatWorkspace.test.tsx`

**Interfaces:**
- Consumes: Task 8 API functions and stream events.
- Produces: One stateful hook for selection, CRUD, streaming updates, stop, retry-ready errors, and stale-request protection.

- [ ] **Step 1: Write failing initial-load and selection tests**

Use `renderHook` with mocked APIs. Assert models and conversations load in parallel, the first conversation is selected, its messages load, and an empty conversation list leaves a useful blank workspace.

```ts
await waitFor(() => expect(result.current.ready).toBe(true))
expect(result.current.selectedConversationId).toBe(1)
expect(result.current.messages.map(m => m.content)).toEqual(['历史问题', '历史回答'])
```

- [ ] **Step 2: Write failing stream reducer and stale-request tests**

Assert `message` inserts both messages, `delta` appends only to the matching assistant, `complete/error/cancelled` replaces the final message, and changing conversation prevents late events from overwriting the new view.

Also assert `send()` is disabled while generating and `stop()` calls `AbortController.abort()` while preserving partial text locally as `CANCELLED` until the next history refresh.

- [ ] **Step 3: Run hook tests and verify failure**

Run: `cd tik-agent/ui && npm run test:run -- src/hooks/useChatWorkspace.test.tsx`

Expected: FAIL because the hook is missing.

- [ ] **Step 4: Implement the hook as explicit state transitions**

Expose this stable surface:

```ts
type ChatWorkspace = {
  ready: boolean
  conversations: Conversation[]
  selectedConversationId: number | null
  messages: Message[]
  models: ModelDescriptor[]
  selectedModelId: string
  generating: boolean
  error: string | null
  selectConversation(id: number): Promise<void>
  createConversation(): Promise<void>
  renameConversation(id: number, title: string): Promise<void>
  deleteConversation(id: number): Promise<void>
  send(content: string): Promise<void>
  stop(): void
  clearError(): void
}
```

Track a monotonically increasing selection request token and ignore stale history responses. Keep one AbortController ref and clear it in `finally` only when it still belongs to the current generation.

- [ ] **Step 5: Run hook tests and commit**

Run: `cd tik-agent/ui && npm run test:run -- src/hooks/useChatWorkspace.test.tsx`

Expected: PASS.

```bash
git add tik-agent/ui/src/hooks
git commit -m "feat: manage chat workspace state"
```

---

### Task 10: Build and visually verify the responsive chat workspace

**Files:**
- Create: `tik-agent/ui/src/App.tsx`
- Create: `tik-agent/ui/src/components/AppHeader.tsx`
- Create: `tik-agent/ui/src/components/ConversationSidebar.tsx`
- Create: `tik-agent/ui/src/components/MessageList.tsx`
- Create: `tik-agent/ui/src/components/ChatComposer.tsx`
- Create: `tik-agent/ui/src/components/EmptyState.tsx`
- Create: `tik-agent/ui/src/styles/global.css`
- Create: `tik-agent/ui/src/components/ChatWorkspace.test.tsx`
- Modify: `tik-agent/ui/src/main.tsx`

**Interfaces:**
- Consumes: Task 9 `useChatWorkspace`.
- Produces: Complete desktop/mobile chat UI with keyboard-safe input, model selection, conversation actions, status rendering, and stop generation.

- [ ] **Step 1: Write failing component workflow tests**

Test visible and accessible behavior rather than implementation details:

```ts
await user.type(screen.getByRole('textbox', { name: '消息' }), '你好')
await user.click(screen.getByRole('button', { name: '发送' }))
expect(workspace.send).toHaveBeenCalledWith('你好')

expect(screen.getByText('生成已取消')).toBeVisible()
await user.click(screen.getByRole('button', { name: '删除会话' }))
expect(workspace.deleteConversation).toHaveBeenCalledWith(7)
```

Cover create, select, inline rename, confirmed delete, model select, Enter-to-send, Shift+Enter newline, generating stop button, empty state, failed message, and mobile sidebar toggle.

- [ ] **Step 2: Run component tests and verify failure**

Run: `cd tik-agent/ui && npm run test:run -- src/components/ChatWorkspace.test.tsx`

Expected: FAIL because components do not exist.

- [ ] **Step 3: Implement the chat layout and controls**

Use Lucide icons for new chat, menu, rename, delete, send, stop, and close actions. Every icon-only button has an `aria-label` and tooltip. Use a native `select` for models, a textarea composer, and stable dimensions for all icon buttons.

UI behavior:

- Sidebar width is fixed on desktop and becomes a modal drawer below `768px`.
- Messages are unframed rows in the main transcript; user and assistant roles remain visually distinct without nested cards.
- Composer stays at the bottom without covering the final message.
- Long code/URLs wrap or scroll inside the transcript; no content can widen the page.
- `STREAMING`, `FAILED`, and `CANCELLED` render visible status treatments without replacing partial content.
- There is no landing page; empty state and composer are the first-use experience.

- [ ] **Step 4: Implement restrained responsive styling**

Use neutral surfaces, dark text, green accent for primary action, amber for warnings, and red only for destructive/error states. Keep cards at `8px` radius or less, letter spacing `0`, fixed font sizes, visible focus rings, and respect `prefers-reduced-motion`.

- [ ] **Step 5: Run UI tests and production build**

Run: `cd tik-agent/ui && npm run test:run`

Expected: PASS.

Run: `cd tik-agent/ui && npm run build`

Expected: PASS.

- [ ] **Step 6: Start the UI against the backend and inspect desktop/mobile screenshots**

Run backend with a local MySQL and Mock model, then run: `cd tik-agent/ui && npm run dev -- --host 127.0.0.1`

Use browser automation at `1440x900`, `768x1024`, and `390x844`. Verify no overlaps, horizontal page scrolling, clipped labels, blank states, or composer obstruction. Exercise new chat, send, stop, rename, delete, and refresh-history flows. Fix visual defects before proceeding.

- [ ] **Step 7: Commit the UI**

```bash
git add tik-agent/ui/src
git commit -m "feat: build responsive chat workspace"
```

---

### Task 11: Package Docker deployment, performance smoke tests, and operator documentation

**Files:**
- Create: `tik-agent/server/Dockerfile`
- Create: `tik-agent/ui/Dockerfile`
- Create: `tik-agent/ui/nginx.conf`
- Create: `tik-agent/docker-compose.yml`
- Create: `tik-agent/.env.example`
- Create: `tik-agent/scripts/perf-smoke.sh`
- Create: `tik-agent/docs/部署说明.md`
- Create: `tik-agent/docs/接口说明.md`
- Modify: `tik-agent/docs/工作进度/一期工作.md`

**Interfaces:**
- Consumes: Complete server/UI applications.
- Produces: `docker compose up --build` local deployment, health checks, repeatable baseline measurements, and complete usage/API documentation.

- [ ] **Step 1: Write deployment smoke assertions before Docker files**

Create `scripts/perf-smoke.sh` with strict mode and arguments `BASE_URL`, `CONCURRENCY` default `20`, and `DURATION` default `30`. Its first phase must fail clearly until deployment exists:

```bash
curl --fail --silent "$BASE_URL/actuator/health" | jq -e '.status == "UP"'
curl --fail --silent "$BASE_URL/api/v1/models" | jq -e 'map(.id) | index("mock") != null'
```

The script creates one conversation per SSE worker, measures time to the first `event: message`, performs concurrent conversation-list reads, and prints request count, failures, P50, P95, and SSE first-event P95. Exit nonzero for any server error or SSE connection failure.

- [ ] **Step 2: Build production images and Compose services**

Server Dockerfile uses Maven/JDK build stage and Java 17 runtime. UI Dockerfile uses Node build stage and Nginx runtime. Nginx configuration must include:

```nginx
location /api/ {
    proxy_pass http://server:8080;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 180s;
}
location / { try_files $uri /index.html; }
```

Compose requirements:

- `mysql`: `mysql:8.4`, named volume, UTF-8, health check.
- `server`: waits for healthy MySQL, receives JDBC and Agent variables, exposes actuator health.
- `ui`: waits for healthy server, publishes `8080:80`, and has an HTTP health check.
- No real credential value appears in Compose or `.env.example`.

- [ ] **Step 3: Document exact configuration and API usage**

`部署说明.md` must include prerequisites, Mock-only quick start, OpenAI-compatible variables, rebuild/reset commands, persistent volume behavior, ports, health checks, logs, and common SSE proxy failures.

`接口说明.md` must include curl examples for all seven endpoints, JSON responses, all SSE event examples, status/error codes, cancellation semantics, no-resume behavior, and the one-generation-per-conversation rule.

Update `一期工作.md` from a wish list to a checklist with implementation scope, technical solution links, verification commands, and a result table. Do not mark an item complete until its verification succeeds in Step 5.

- [ ] **Step 4: Run static build and configuration checks**

Run: `cd tik-agent/server && mvn test`

Expected: PASS.

Run: `cd tik-agent/ui && npm run test:run && npm run build`

Expected: PASS.

Run: `cd tik-agent && docker compose config`

Expected: valid configuration with no interpolation warnings except intentionally blank optional OpenAI credentials.

- [ ] **Step 5: Run the complete Docker acceptance flow**

Run: `cd tik-agent && docker compose up --build -d`

Wait for all three services to become healthy, then verify through `http://localhost:8080`:

1. UI loads and lists Mock.
2. Create a conversation and receive multiple SSE deltas.
3. Refresh and recover persisted history.
4. Rename and delete a conversation.
5. Stop one Mock response and observe `CANCELLED` after history refresh.
6. Start two requests for one conversation and receive `409` for the second.
7. Restart `server` and verify MySQL history remains.

Run: `cd tik-agent && BASE_URL=http://localhost:8080 ./scripts/perf-smoke.sh`

Expected: no server/SSE failures; list endpoint test completes at 20 concurrent workers for 30 seconds; SSE first business event P95 is below 1 second with Mock.

- [ ] **Step 6: Perform final responsive browser verification**

Capture and inspect the Docker-served UI at `1440x900` and `390x844`. Verify all assets load through Nginx, the SSE stream is incremental rather than buffered, mobile controls do not overlap, and the transcript remains usable with a long response.

- [ ] **Step 7: Record real results and commit deployment/docs**

Write the actual test commands, date, environment, pass/fail status, and measured P50/P95 values into `一期工作.md`. Never insert invented measurements.

```bash
git add tik-agent/server/Dockerfile tik-agent/ui/Dockerfile tik-agent/ui/nginx.conf tik-agent/docker-compose.yml tik-agent/.env.example tik-agent/scripts tik-agent/docs
git commit -m "docs: package and verify phase one deployment"
```

---

## Final Verification Gate

- [ ] Run `cd tik-agent/server && mvn clean test` and retain the passing summary.
- [ ] Run `cd tik-agent/ui && npm run test:run && npm run build` and retain test/build summaries.
- [ ] Run `cd tik-agent && docker compose config` and verify secrets are not rendered from tracked files.
- [ ] Run the complete Docker acceptance flow and performance smoke script from Task 11.
- [ ] Inspect `git status --short`; confirm only intended implementation files changed and preserve unrelated `.idea` work.
- [ ] Compare delivered behavior against every acceptance criterion in the design spec before claiming phase one complete.
