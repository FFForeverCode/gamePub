# tik-agent 一期聊天系统设计

## 1. 背景与目标

tik-agent 一期以最小可用的单用户聊天系统为目标，完成从浏览器输入问题、后端调用模型、SSE 流式返回，到会话历史持久化和再次查看的完整闭环。

一期交付必须满足：

- 提供类似 ChatGPT 的聊天界面，支持会话管理、模型选择、问题输入和流式回答。
- 提供稳定的 REST 与 SSE 接口，支持 Mock 模型和 OpenAI 兼容模型。
- 使用 MySQL 保存会话及消息历史，服务重启后可以恢复。
- 使用本地内存保存短期上下文，缓存未命中时可由数据库重建。
- 使用 Docker Compose 在本地一键启动前端、后端和 MySQL。
- 提供自动化测试、基础性能测试脚本和部署说明。

## 2. 一期范围

### 2.1 包含

- 单用户、无登录模式。
- 新建、查看、切换、重命名和删除会话。
- 发送文本消息并通过 SSE 接收流式回答。
- 在 Mock 与已配置的 OpenAI 兼容模型之间切换。
- 停止正在生成的回答。
- 展示历史消息以及失败或取消状态。
- MySQL 数据迁移、Docker 本地部署、健康检查。

### 2.2 不包含

- 账号、认证、权限和多租户隔离。
- 长期记忆、向量检索、知识库和 RAG。
- 工具调用、工作流、智能体编排。
- 图片、语音和文件消息。
- Redis、Elasticsearch、Kafka。
- 公网服务器、域名、HTTPS 和云资源部署。

这些能力保留为后续扩展项，不在一期代码中预留空实现。

## 3. 技术方案

### 3.1 技术栈

| 层级 | 技术 | 用途 |
| --- | --- | --- |
| 前端 | React、Vite、TypeScript | 单页聊天应用 |
| 前端数据请求 | Fetch API | REST 请求与 POST SSE 流解析 |
| 后端 | Java 17、Spring Boot | HTTP API、业务编排和配置管理 |
| Agent 框架 | Spring AI | Agent 会话、提示词、消息抽象及流式模型调用 |
| 流式传输 | Spring MVC `SseEmitter`、Reactor `Flux` | 向浏览器推送模型增量 |
| 持久层 | MyBatis | Mapper 接口与 XML SQL |
| 数据库 | MySQL 8 | 会话和消息历史 |
| 数据迁移 | Flyway | 数据库结构版本管理 |
| 短期记忆 | Caffeine | 进程内最近消息缓存 |
| 测试 | JUnit、Testcontainers、Vitest、Testing Library | 后端及前端自动化测试 |
| 部署 | Docker、Docker Compose、Nginx | 本地容器化运行 |

### 3.2 总体架构

项目保持单仓库，前后端独立构建，后端采用模块化单体：

```text
React Web
   | REST + SSE
   v
Spring Boot
   |-- conversation  会话及历史消息管理
   |-- chat          上下文组装、流式编排和生成任务管理
   |-- agent         Spring AI Agent、模型目录及 Mock 适配器
   `-- persistence   MyBatis Mapper、实体及数据库迁移
          |
          v
        MySQL
```

`conversation`、`chat` 和 `agent` 通过明确的 Service 接口协作。Controller 不直接调用 Mapper，Spring AI Agent 也不负责消息落库。

### 3.3 选型说明

采用模块化单体是因为一期业务规模有限，独立模型网关会增加部署与故障处理成本。Agent 能力统一使用 Spring AI 实现，不引入另一套 Agent 编排框架；后续确有独立扩缩容需求时，可将 Spring AI Agent 模块迁移为单独服务。

持久层采用原生 MyBatis Mapper 加 XML SQL。它便于明确控制会话列表、最近消息和序号分配等查询，并避免 ORM 隐式行为。Flyway 单独负责表结构演进。

浏览器原生 `EventSource` 不支持携带 POST 请求体，因此前端使用 `fetch` 读取 `text/event-stream`，按 SSE 帧解析事件，并通过 `AbortController` 支持停止生成。

## 4. 代码模块

### 4.1 前端

建议目录：

```text
web/src/
  api/             REST 客户端与 SSE 解析器
  components/      会话侧栏、消息列表、输入区、模型选择器
  hooks/           会话加载与流式聊天状态
  pages/           聊天主页面
  types/           接口数据类型
  styles/          全局变量及响应式样式
```

页面第一屏直接进入聊天工作区。桌面端为左侧会话列表和右侧聊天区，窄屏下侧栏变为可开合抽屉。流式生成期间输入区提供停止按钮，同一会话不允许重复提交。

### 4.2 后端

建议包结构：

```text
com.gamepub.server
  common/          错误码、统一异常响应、基础配置
  conversation/    Controller、Service、DTO、Mapper、Entity
  chat/            流式编排、生成任务注册表、短期记忆
  agent/           Spring AI ChatClient、模型目录及 Mock 适配器
```

关键边界：

- `ConversationService`：管理会话和历史查询。
- `ChatService`：校验生成状态、保存用户消息、读取上下文、驱动 Agent 流并完成助手消息。
- `ShortTermMemory`：按会话读取或重建最近消息，不承担持久化职责。
- `SpringAiAgentService`：使用 Spring AI `ChatClient` 组装系统提示词与历史消息，并返回 `Flux<String>` 增量。
- `ChatClientRegistry`：按模型 ID 管理 Spring AI `ChatModel` 和 `ChatClient`，提供统一的模型选择入口。
- `GenerationRegistry`：记录会话当前生成任务，保证每个会话最多一个活动流，并处理取消。

## 5. 数据设计

### 5.1 conversations

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | 主键 | 雪花或数据库生成的会话 ID |
| title | VARCHAR(120) | 非空 | 默认取首条问题的前 30 个字符 |
| created_at | DATETIME(3) | 非空 | 创建时间 |
| updated_at | DATETIME(3) | 非空、索引 | 最近消息或改名时间 |

### 5.2 messages

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | 主键 | 消息 ID |
| conversation_id | BIGINT | 外键、索引 | 所属会话 |
| role | VARCHAR(16) | 非空 | `USER` 或 `ASSISTANT` |
| content | MEDIUMTEXT | 非空 | 完整消息内容，可为空字符串起始 |
| status | VARCHAR(16) | 非空 | `STREAMING`、`COMPLETED`、`FAILED`、`CANCELLED` |
| model_id | VARCHAR(100) | 可空 | 助手消息所用模型；用户消息为空 |
| sequence_no | BIGINT | 非空 | 会话内单调递增序号 |
| error_code | VARCHAR(64) | 可空 | 失败时的稳定错误码 |
| created_at | DATETIME(3) | 非空 | 创建时间 |
| updated_at | DATETIME(3) | 非空 | 更新时间 |

`(conversation_id, sequence_no)` 建立唯一索引。删除会话时通过外键级联删除消息，业务层仍以事务显式执行并校验目标是否存在。

一期单实例运行，通过查询当前最大序号并在事务内写入消息。未来支持多实例时，应改为数据库锁、独立序列或有序 ID 方案。

## 6. API 设计

统一前缀为 `/api/v1`，JSON 时间使用 ISO 8601。普通接口错误响应格式为：

```json
{
  "code": "CONVERSATION_NOT_FOUND",
  "message": "会话不存在",
  "requestId": "..."
}
```

### 6.1 模型

```text
GET /api/v1/models
```

返回可用模型的 `id`、显示名称、提供方及是否默认。未配置 API Key 的真实模型不出现在可用列表中，`mock` 默认可用。

### 6.2 会话

```text
GET    /api/v1/conversations
POST   /api/v1/conversations
PATCH  /api/v1/conversations/{id}
DELETE /api/v1/conversations/{id}
GET    /api/v1/conversations/{id}/messages
```

会话列表按 `updated_at DESC, id DESC` 排序。一期数据量较小，接口使用 `limit` 和 `beforeId` 游标参数，默认每页 30 条，避免固定为全量查询。

### 6.3 流式聊天

```text
POST /api/v1/conversations/{id}/messages/stream
Content-Type: application/json
Accept: text/event-stream

{
  "content": "用户问题",
  "modelId": "mock"
}
```

SSE 事件：

```text
event: message
data: {"userMessage": {...}, "assistantMessage": {...}}

event: delta
data: {"messageId": 2, "content": "增量文本"}

event: complete
data: {"message": {...}}

event: error
data: {"messageId": 2, "code": "MODEL_TIMEOUT", "message": "模型响应超时"}

event: cancelled
data: {"message": {...}}
```

首个 `message` 事件用于让前端拿到两个已创建消息的 ID。每个事件包含递增 SSE `id`，但一期不实现断线续传；页面重新加载后从历史接口读取最终持久化状态。

## 7. 流式处理与一致性

一次提问按以下顺序处理：

1. 校验会话、模型、内容长度以及该会话是否已有活动生成任务。
2. 在事务中写入用户消息和状态为 `STREAMING` 的助手消息，同时更新会话时间及默认标题。
3. 注册生成任务，向前端发送 `message` 事件。
4. 从 Caffeine 获取最近上下文；未命中时从 MySQL 加载最近 20 条已完成消息。
5. `SpringAiAgentService` 将上下文映射为 Spring AI `Message`，通过对应 `ChatClient` 发起流式调用，并把增量同时追加到内存缓冲及发送为 `delta` 事件。
6. 正常结束时将完整助手消息一次性更新为 `COMPLETED`，更新短期记忆并发送 `complete`。
7. 模型异常时保存已经生成的内容，将消息更新为 `FAILED`，发送 `error`。
8. 浏览器取消或连接断开时取消上游订阅，将消息更新为 `CANCELLED`；若连接仍可写则发送 `cancelled`。
9. 无论结果如何都从生成任务注册表移除该会话。

为避免每个 token 写数据库，流式期间只保存在有界内存缓冲中，终止时一次落库。应用异常退出可能留下 `STREAMING` 消息；启动时将超过生成超时时间的这类消息修正为 `FAILED`，错误码为 `GENERATION_INTERRUPTED`。

## 8. 短期记忆

Caffeine 以会话 ID 为键保存最近 20 条可用于模型上下文的消息：

- 只纳入 `COMPLETED` 消息；失败或取消的助手消息不进入后续上下文。
- 30 分钟未访问后过期，最多缓存 1000 个会话。
- 缓存未命中时从数据库加载最近消息并按正序重建。
- 删除会话时同步失效缓存；完成一轮问答后增量更新缓存。

数量、过期时间和最大会话数通过应用配置及环境变量覆盖。Token 上限截断由 Spring AI Agent 模块在发送前执行，一期先按消息条数限制上下文。

## 9. Spring AI Agent 与模型适配

一期 Agent 框架统一使用 Spring AI。`SpringAiAgentService` 负责通过 `ChatClient` 组织系统提示词、历史消息和当前问题，并使用 Spring AI 流式 API 返回内容增量。业务代码不直接拼装供应商请求，也不自行解析上游模型的 SSE 协议。

一期的 Agent 是单轮驱动、携带多轮上下文的对话 Agent，不包含工具调用、规划器或多 Agent 协作。后续增加 Tool Calling、Advisor、RAG 等能力时继续沿用 Spring AI 扩展机制。

- `ChatClientRegistry`：根据配置为每个可用模型创建并注册 Spring AI `ChatModel` 与 `ChatClient`，按 `modelId` 路由。
- `SpringAiAgentService`：将领域消息转换为 Spring AI `UserMessage`、`AssistantMessage` 和 `SystemMessage`，调用 `ChatClient.prompt().messages(...).stream().content()` 获得 `Flux<String>`。
- `MockAgentClient`：无需网络或密钥，按固定间隔输出可预测分片，仅用于本地演示和自动化测试；它实现与 Agent 服务边界一致的测试适配器，不构成第二套 Agent 框架。
- OpenAI 兼容模型：通过 Spring AI OpenAI Starter 创建 `ChatModel`，读取模型名称、Base URL、API Key、温度和超时等配置。
- `ModelCatalog`：生成前端可选模型列表；未完成配置的真实模型不会注册。

系统提示词由后端配置提供，一期使用单一默认模板。提示词配置、Spring AI 对话消息和供应商参数均封装在 `agent` 模块中，`chat` 模块只传入标准会话上下文与所选模型 ID。

密钥只从环境变量读取，不写入代码、镜像或版本库。日志不记录 API Key、完整提示词或完整模型回答。

## 10. 错误处理

流建立前的错误使用 HTTP 状态码和统一 JSON：

- `400`：空问题、内容超长、模型 ID 非法。
- `404`：会话不存在。
- `409`：同一会话已有生成任务。
- `503`：模型不可用或未正确配置。

流建立后的错误使用 SSE `error` 事件，稳定错误码至少包括 `MODEL_TIMEOUT`、`MODEL_RATE_LIMITED`、`MODEL_UNAVAILABLE`、`STREAM_WRITE_FAILED` 和 `INTERNAL_ERROR`。

前端保留失败或取消的局部回答，显示对应状态并允许用户重新发送问题。不自动重试生成请求，以免产生重复消息和额外模型费用。

## 11. 配置与本地部署

Docker Compose 包含：

- `web`：Nginx 托管前端静态文件，并将 `/api` 反向代理到后端，关闭 SSE 响应缓冲。
- `server`：Spring Boot 应用，等待 MySQL 健康后启动并执行 Flyway。
- `mysql`：MySQL 8，使用命名卷保存数据。

仓库提供 `.env.example`，至少包含数据库连接、默认模型、OpenAI 兼容 Base URL、API Key 和模型名称。默认不填写真实模型密钥，系统自动使用 Mock 模型。

所有容器提供健康检查。开发环境允许分别启动 Vite 和 Spring Boot；生产构建使用多阶段镜像减少最终体积。

## 12. 测试方案

### 12.1 后端

- 单元测试：标题生成、模型路由、缓存裁剪、错误映射和生成任务互斥。
- Mapper 集成测试：使用 Testcontainers MySQL 验证建表迁移、排序、游标和级联删除。
- API 集成测试：会话 CRUD、历史查询以及 SSE 的 `message/delta/complete/error/cancelled` 路径。
- 恢复测试：验证遗留 `STREAMING` 消息在启动后被标记失败。

### 12.2 前端

- SSE 解析器测试：分块边界、多个事件、JSON 错误和流终止。
- 状态测试：增量拼接、完成、失败、取消和会话切换。
- 组件测试：创建/删除会话、选择模型、提交问题和停止生成。
- 构建检查与关键页面响应式截图检查。

### 12.3 基础性能

提供可重复执行的测试脚本并记录机器配置、并发数、请求量、成功率、P50/P95 响应时间和 SSE 首事件延迟。Mock 模型场景的建议基线为：

- 会话与历史查询：20 并发、持续 30 秒，无服务端错误。
- SSE：20 个并发流，连接成功率 100%，首个业务事件 P95 小于 1 秒。

该基线用于发现明显回归，不代表生产容量承诺。真实模型延迟和限流单独记录，不作为本地服务性能判定依据。

## 13. 验收标准

一期完成需同时满足：

1. `docker compose up --build` 可启动三项服务，浏览器可直接进入聊天页面。
2. 无 API Key 时可选择 Mock 模型并看到逐步输出。
3. 配置 OpenAI 兼容服务后模型出现在列表中并可完成流式回答。
4. 可创建、切换、重命名、删除会话，刷新页面后历史仍存在。
5. 可停止生成；失败、取消和中断状态在历史中准确显示。
6. 同一会话重复生成被拒绝，不产生乱序消息。
7. 后端和前端自动化测试、构建检查全部通过。
8. 部署文档、配置示例、接口说明和基础性能测试结果齐全。

## 14. 后续演进

后续阶段可在不改变前端核心会话契约的前提下增加用户身份、Redis 分布式生成状态、基于 token 的上下文窗口、长期记忆/RAG、工具调用和独立模型网关。任何多实例部署都必须先替换一期的本地缓存、进程内任务注册表和单实例消息序号方案。
