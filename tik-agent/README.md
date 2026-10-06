# tik-agent

tik-agent 是一个本地优先的单用户 AI 聊天应用。一期实现了会话管理、MySQL 历史持久化、Caffeine 短期上下文、Spring AI 模型接入和基于 POST 的 SSE 流式回答。默认使用内置 Mock 模型，无需 API Key 即可运行完整链路。

## 一期功能

- 新建、切换、重命名、删除会话
- 查看持久化历史消息
- SSE 增量展示模型回答并支持停止生成
- 每个会话最多一个活动生成任务
- 内置 Mock 模型和可选 OpenAI 兼容模型
- 服务重启后修复中断的流式消息
- Docker Compose 本地一键部署

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | React 19、TypeScript、Vite、Lucide React |
| 后端 | Java 17、Spring Boot 4、Spring MVC |
| Agent | Spring AI 2、Reactor Flux |
| 数据 | MyBatis、MySQL 8.4、Flyway、Caffeine |
| 部署 | Docker Compose、Nginx |
| 测试 | JUnit 5、Testcontainers、Vitest |

## 目录结构

```text
tik-agent/
  server/              Spring Boot 后端和 Spring AI Agent
  ui/                  React 聊天前端
  docs/                设计、技术方案、接口和部署文档
  scripts/             本地验收脚本
  docker-compose.yml   MySQL、后端、前端编排
  .env.example         环境变量模板
```

## 快速启动

### Docker Compose

环境要求：Docker Desktop 或兼容的 Docker Engine，Docker Compose v2。

```bash
cd tik-agent
cp .env.example .env
docker compose up --build
```

启动后访问：

- Web UI: <http://localhost:5173>
- 后端健康检查: <http://localhost:8080/actuator/health>
- MySQL: `localhost:3306`

默认 `TIK_AGENT_DEFAULT_MODEL=mock`，无需配置模型密钥。停止服务使用：

```bash
docker compose down
```

MySQL 数据保存在 `tik-agent-mysql` Docker volume。仅在确认不需要历史数据时使用 `docker compose down -v`。

### 本地开发

先启动 MySQL：

```bash
docker compose up -d mysql
```

启动后端：

```bash
cd server
mvn spring-boot:run
```

另开终端启动前端：

```bash
cd ui
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:8080`。

## OpenAI 兼容模型

在根目录 `.env` 中配置：

```dotenv
TIK_AGENT_DEFAULT_MODEL=openai
TIK_AGENT_OPENAI_ENABLED=true
TIK_AGENT_OPENAI_BASE_URL=https://api.openai.com/v1
TIK_AGENT_OPENAI_API_KEY=your-api-key
TIK_AGENT_OPENAI_MODEL_NAME=gpt-4.1-mini
TIK_AGENT_OPENAI_TEMPERATURE=0.2
```

然后执行 `docker compose up --build`。只有启用且 `base-url`、`api-key`、`model-name` 都完整时，真实模型才会出现在前端模型列表。API Key 只通过环境变量注入，不写入数据库和日志。

## API 与 SSE

主要接口：

```text
GET    /api/v1/models
GET    /api/v1/conversations
POST   /api/v1/conversations
PATCH  /api/v1/conversations/{id}
DELETE /api/v1/conversations/{id}
GET    /api/v1/conversations/{id}/messages
POST   /api/v1/conversations/{id}/messages/stream
```

流式接口使用 `POST` 和 `Accept: text/event-stream`，事件依次为 `message`、多个 `delta`，最终为 `complete`、`error` 或 `cancelled`。浏览器端使用 `fetch` 读取字节流，因为原生 `EventSource` 不支持 POST body。

完整示例见 [接口说明](docs/接口说明.md)。

## 测试与构建

```bash
# 后端编译
cd server && mvn -DskipTests package

# 后端测试（数据库集成测试需要 Docker）
cd server && mvn test

# 前端测试和生产构建
cd ui && npm test -- --run
cd ui && npm run build

# 服务启动后的 REST/SSE 冒烟
./scripts/perf-smoke.sh
```

## 配置摘要

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本地 `tik_agent` | JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `tik_agent` | 数据库账户 |
| `TIK_AGENT_DEFAULT_MODEL` | `mock` | 默认模型 ID |
| `TIK_AGENT_STREAM_TIMEOUT` | `120s` | 单次流式生成超时 |
| `TIK_AGENT_MEMORY_MESSAGE_LIMIT` | `20` | 上下文消息数 |
| `TIK_AGENT_MEMORY_EXPIRE_AFTER_ACCESS` | `30m` | 本地缓存过期时间 |

## 常见问题

- UI 无法访问 API：确认后端 `8080` 端口可用，开发模式由 Vite 代理，容器模式由 Nginx 代理。
- 真实模型没有显示：检查四个 `TIK_AGENT_OPENAI_*` 必填变量，并重新构建或重启 server。
- 数据库启动慢：首次拉取 MySQL 镜像和初始化数据目录需要等待健康检查通过。
- 后端集成测试跳过：启动 Docker Desktop 后重新执行 `mvn test`。
- 回答中断后显示 `STREAMING`：服务启动会把超过流超时的遗留消息修正为 `FAILED/GENERATION_INTERRUPTED`。

更多内容：

- [一期技术方案说明](docs/技术方案/一期技术方案说明.md)
- [部署说明](docs/部署说明.md)
- [接口说明](docs/接口说明.md)
- [一期设计](docs/superpowers/specs/2026-09-19-phase-one-chat-design.md)
- [一期实施计划](docs/superpowers/plans/2026-09-19-phase-one-chat-implementation.md)
