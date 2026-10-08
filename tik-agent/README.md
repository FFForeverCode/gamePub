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

下面给出完整启动顺序。第一次启动建议使用 Docker Compose，它会自动启动 MySQL、执行 Flyway 建表、启动后端和发布前端。

### 1. 环境要求

Docker 方式：

- Docker Desktop 或兼容的 Docker Engine
- Docker Compose v2
- 可用端口：`5173`、`8080`、`3307`

本地开发方式还需要：

- JDK 17+
- Maven 3.9+（或使用项目环境中的 Maven Wrapper）
- Node.js 20+、npm 10+

检查环境：

```bash
docker --version
docker compose version
java -version
mvn -version
node --version
npm --version
```

### 2. 配置环境变量

在项目根目录执行：

```bash
cd /Users/netchen/Desktop/gamePub/tik-agent
cp .env.example .env
```

`.env` 是本机配置文件，不要提交到 Git。默认配置使用 Mock 模型，不需要 API Key：

```dotenv
DB_USERNAME=tik_agent
DB_PASSWORD=tik_agent
DB_ROOT_PASSWORD=root
MYSQL_PORT=3307
SERVER_PORT=8080
UI_PORT=5173
TIK_AGENT_DEFAULT_MODEL=mock
TIK_AGENT_OPENAI_ENABLED=false
```

### 3. Docker Compose 一键启动

确保 Docker Desktop 已启动，然后执行：

```bash
docker compose up --build -d
```

启动顺序如下：

1. Compose 启动 MySQL，并等待 healthcheck 变为 `healthy`。
2. Spring Boot 连接 MySQL，显式执行 `classpath:db/migration/V1__create_chat_schema.sql`。
3. Flyway 创建 `flyway_schema_history`、`conversations` 和 `messages` 表。
4. 后端启动 HTTP 服务，默认监听 `8080`。
5. Nginx 启动前端，默认监听 `5173`，并将 `/api` 反向代理到后端。

查看容器状态：

```bash
docker compose ps
docker compose logs -f server
```

确认后端健康：

```bash
curl http://localhost:8080/actuator/health
```

浏览器访问：<http://localhost:5173>

停止但保留数据：

```bash
docker compose down
```

删除容器并清空 MySQL 历史数据：

```bash
docker compose down -v
```

### 4. 本地开发启动

#### 4.1 只使用 Docker 启动 MySQL

```bash
cd /Users/netchen/Desktop/gamePub/tik-agent
docker compose up -d mysql
docker compose ps mysql
```

等待 MySQL 显示 `healthy` 后，再启动后端。默认 JDBC 配置为：

```text
jdbc:mysql://localhost:3307/tik_agent
用户名：tik_agent
密码：tik_agent
```

如果你使用了其他数据库实例，可以通过环境变量覆盖：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3307/tik_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
export DB_USERNAME=tik_agent
export DB_PASSWORD=tik_agent
```

#### 4.2 启动后端

另开终端：

```bash
cd /Users/netchen/Desktop/gamePub/tik-agent/server
mvn spring-boot:run
```

看到以下日志表示后端已经启动：

```text
Tomcat started on port 8080
Started ServerApplication
```

启动时会自动执行 Flyway 迁移。可以检查表是否存在：

```bash
mysql -h 127.0.0.1 -P 3306 -u tik_agent -p tik_agent \
  -e 'SHOW TABLES;'
```

#### 4.3 启动前端

再开一个终端：

```bash
cd /Users/netchen/Desktop/gamePub/tik-agent/ui
npm install
npm run dev
```

浏览器访问 <http://localhost:5173>。Vite 会把 `/api` 请求代理到 `http://localhost:8080`，不需要在前端填写后端地址。

前端生产构建：

```bash
npm run build
npm run preview
```

### 5. 数据源配置流程

#### 5.1 MySQL 配置

Docker Compose 使用 `docker-compose.yml` 中的 MySQL 服务创建：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `DB_USERNAME` | `tik_agent` | 应用数据库用户 |
| `DB_PASSWORD` | `tik_agent` | 应用数据库密码 |
| `DB_ROOT_PASSWORD` | `root` | MySQL root 密码 |
| `MYSQL_PORT` | `3307` | 宿主机映射端口，容器内仍为 `3306` |
| `DB_URL` | Compose 内部地址 | 后端 JDBC 连接串 |

容器内后端使用主机名 `mysql`，本地启动后端使用 `localhost`。这两个地址不能混用：

```text
Docker 后端：jdbc:mysql://mysql:3306/tik_agent
本地后端：  jdbc:mysql://localhost:3307/tik_agent
```

#### 5.2 Mock 模型

Mock 是默认数据源，不依赖外部网络和模型凭证：

```dotenv
TIK_AGENT_DEFAULT_MODEL=mock
TIK_AGENT_OPENAI_ENABLED=false
```

启动后访问 `GET /api/v1/models`，应能看到 `mock`。Mock 适合验证会话、数据库、SSE 和前端流程。

#### 5.3 OpenAI 兼容模型

在根目录 `.env` 中配置完整信息：

```dotenv
TIK_AGENT_DEFAULT_MODEL=openai
TIK_AGENT_OPENAI_ENABLED=true
TIK_AGENT_OPENAI_BASE_URL=https://api.openai.com/v1
TIK_AGENT_OPENAI_API_KEY=your-api-key
TIK_AGENT_OPENAI_MODEL_NAME=gpt-4.1-mini
TIK_AGENT_OPENAI_TEMPERATURE=0.2
```

重启后端：

```bash
docker compose up --build -d server
# 或本地启动时重新执行 mvn spring-boot:run
```

只有 `enabled=true` 且 `base-url`、`api-key`、`model-name` 都非空时，模型才会出现在前端下拉框。API Key 只通过环境变量注入，不写入数据库和日志。

### 6. 启动后的验收流程

```bash
# 1. 查看模型
curl http://localhost:8080/api/v1/models

# 2. 创建会话
curl -X POST http://localhost:8080/api/v1/conversations

# 3. 使用返回的 id 测试 SSE
curl -N -X POST http://localhost:8080/api/v1/conversations/1/messages/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"content":"请回复健康检查成功","modelId":"mock"}'

# 4. 查看消息历史
curl http://localhost:8080/api/v1/conversations/1/messages
```

也可以直接运行项目自带脚本：

```bash
./scripts/perf-smoke.sh
```

### 7. 端口和访问地址

| 服务 | 默认地址 | 说明 |
| --- | --- | --- |
| 前端 | `http://localhost:5173` | 浏览器访问入口 |
| 后端 | `http://localhost:8080` | REST、SSE、Actuator |
| MySQL | `localhost:3307` | 仅本地开发或数据库工具访问 |

如果端口被占用，在 `.env` 中修改 `UI_PORT`、`SERVER_PORT` 或 `MYSQL_PORT`，并同步确认 `DB_URL` 使用正确的 MySQL 端口。

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
