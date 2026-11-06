# 心理健康 AI 助手

面向大学生的 AI 心理支持平台，前后端分离。后端完整实现了**认证鉴权**与**AI 对话链路**，前端覆盖咨询、情绪日记、知识库与数据看板。

- 后端：Java 17 + Spring Boot 3 + Spring AI + Spring Security/JWT + MyBatis-Plus + MySQL 8
- 前端：Vue 3 + Vite + Element Plus + Pinia + ECharts
- 模型：DeepSeek（OpenAI 兼容协议），SSE 流式输出

## 功能概览

| 模块 | 说明 | 实现情况 |
|---|---|---|
| 注册 / 登录 / 当前用户 | JWT 无状态鉴权、BCrypt 加密、DTO 参数校验、统一返回与全局异常 | 后端完整实现 |
| AI 心理对话 | 两段式会话创建 + SSE 流式输出，ChatMemory 按会话隔离，仅保留最近 30 条消息 | 后端完整实现 |
| 情绪日记 / 知识库 / 数据看板 | 9 张表结构 + 前端页面，联调阶段对接完整版后端接口 | 表结构与前端页面 |

## 目录结构

```
├── 后端代码/ai-spingboot/        # Spring Boot 后端（端口 1236，统一前缀 /api）
├── 前端代码/ai-vue/              # Vue 3 前端（Vite）
├── auth-flow-diagrams/           # 登录、注册、获取当前用户的 Mermaid 时序图
├── 环境设置/资料/mental_health_assistant.sql   # 建表脚本（其余环境安装包已被 .gitignore 排除）
├── 后端项目结构说明.md            # 后端分层、类职责与接口说明
└── 前端项目结构说明.md            # 前端目录、路由与页面说明
```

## 快速开始

### 1. 准备数据库

```sql
CREATE DATABASE mental_health_assistant DEFAULT CHARACTER SET utf8mb4;
```

然后导入 [mental_health_assistant.sql](环境设置/资料/mental_health_assistant.sql)。共 9 张表：

`user`、`consultation_session`、`consultation_message`、`emotion_diary`、`knowledge_category`、`knowledge_article`、`ai_analysis_task`、`user_favorite`、`sys_file_info`

### 2. 启动后端

需要 JDK 17。密钥通过环境变量注入，仓库中不含任何明文密钥：

```powershell
# PowerShell
$env:DEEPSEEK_API_KEY="sk-xxx"          # 必填，调用 DeepSeek 使用
$env:JWT_SECRET="一段足够长的随机字符串"   # 必填，未设置会启动失败
$env:MYSQL_PASSWORD="123456"            # 可选，默认 123456
```

```bash
cd 后端代码/ai-spingboot
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

启动后访问 `http://localhost:1236/api/test` 应返回 `hello world`。

### 3. 启动前端

```bash
cd 前端代码/ai-vue
npm install
npm run dev
```

> 注意：`vite.config.js` 里的 `/api` 代理默认指向远程完整后端。只想连本地后端时，把 `target` 改成 `http://localhost:1236`。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/add` | 注册 |
| POST | `/api/user/login` | 登录，返回 JWT |
| GET | `/api/user/current` | 获取当前登录用户 |
| POST | `/api/psychological-chat/session/start` | 创建会话并落库首条消息 |
| POST | `/api/psychological-chat/stream` | SSE 流式对话（`text/event-stream`） |
| GET | `/api/test` | 连通性测试 |

请求头统一使用 `token: <JWT>`（当前过滤器读取 `token` 头）。

## 关键设计

**两段式会话（session/start + stream）**
先建会话、落库用户首条消息并返回 `sessionId`，再由 `stream` 负责生成。模型失败时首条提问不会丢，会话列表也能立即看到；代价是需要处理首条消息重复入库，代码里用「消息数 + 内容」双重校验规避。

**30 条滑动窗口控制上下文成本**
`MessageWindowChatMemory(maxMessages = 30)` 按会话隔离，超出后滑出最早的整条消息。本地实测（DeepSeek 官方接口，30 轮连续对话）：

- 输入 token 在第 16 轮左右进入平台期，稳定在 **1.0–1.1k / 轮**
- 同一对话不裁剪历史时线性增长到 **2.1k / 轮**，第 30 轮窗口策略**省约 50% 输入 token**
- 流式首字延迟 **p50 ≈ 0.8s**（受网络与模型负载影响，非线上 SLA）

**SSE 输出**
后端用 `Flux` 把「查库 → 写库 → 调模型」桥接成流，每个分片包成 `event: message`，结束时补 `event: done`；前端用 `fetch-event-source` 发 POST + 自定义 header，支持中途 abort。

## 已知限制

- `/stream` 只按 `sessionId` 查会话，未校验会话归属，存在水平越权（IDOR）风险，修复方式是查询条件加上 `userId`
- `ChatMemory` 为进程内 Map，重启丢失、多实例不一致，生产应换 JDBC / Redis 实现
- 知识库、情绪日记、后台统计等接口依赖完整版后端，本仓库后端只实现了上表中的 6 个接口
- 未接入 Redis / 消息队列，属于单机部署规模下的有意取舍

## 作者

韦坚 · 合肥工业大学 物联网工程
