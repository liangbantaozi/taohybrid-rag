# TaoHybridRAG

[English](README.md) | 简体中文

TaoHybridRAG 是一个面向企业知识库问答场景的 Java + Vue RAG 系统，提供异步文档入库、混合检索、权限过滤、ReAct 工具调用、流式对话和可追溯引用。

项目定位是一个可检查、可评测、可持续优化的 RAG 工程基线：检索行为具有真实评测入口，GraphRAG 是可选且可观测的实验能力，Graph 异常不会阻塞主要的 Hybrid 检索链路。

## 功能预览

### 带原文引用的知识库问答

![带原文引用的知识库问答](docs/images/1.png)

### 知识库入库与处理状态

![知识库入库与处理状态](docs/images/2.png)

### 引用与来源文档预览

![引用与来源文档预览](docs/images/3.png)

### 知识图谱管理

![知识图谱管理](docs/images/5.png)

## 核心能力

- **文档入库**：文件上传、异步解析、语义分块、向量化、索引和处理状态追踪。
- **混合检索**：基于 Elasticsearch 的 KNN 向量检索与 BM25 关键词检索，并提供可运行的评测链路。
- **证据化回答**：将来源文件、chunk 和页码信息映射回回答，支持引用追溯和原文预览。
- **ReAct 工具调用**：聊天流程可以调用知识库检索、摘要、反馈、知识库统计和可选 Graph 工具。
- **实验性 GraphRAG**：使用 MySQL 存储实体、提及、关系、文档建图状态、权限 metadata 和跨文档关系证据。
- **多租户权限**：支持公开/私有文件、用户所有权、组织标签以及基于角色的管理行为。
- **流式交互**：基于 WebSocket 的流式聊天、断线重连和会话历史。
- **可配置 AI Provider**：LLM 与 Embedding 地址和凭据均通过配置注入，不在源码中绑定特定账号。

## 系统架构

```text
浏览器 / Vue 3
       │ HTTP + WebSocket
       ▼
Spring Boot API 与聊天工作流
       ├── MySQL ───────── 用户、文件、会话、Graph 数据
       ├── Redis ───────── Session、缓存、短期聊天上下文
       ├── Kafka ───────── 异步文档处理任务
       ├── MinIO ───────── 上传的原始文件
       ├── Elasticsearch ─ BM25 + 向量检索
       └── LLM / Embedding Provider
```

后端目前仍使用 `com.yizhaoqi.smartpai` 作为主要 Java 包名。这是当前代码的真实命名空间，不代表项目依赖另一个产品。

## 技术栈

| 领域 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.2、Maven、Spring Security、Spring Data JPA、WebFlux |
| 前端 | Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、UnoCSS |
| 数据库与缓存 | MySQL 8、Redis 7 |
| 检索 | Elasticsearch 8.10.4、BM25、稠密向量 KNN |
| 异步处理 | Apache Kafka |
| 对象存储 | MinIO |
| 文档解析 | Apache Tika 2.9.1、可选 LiteParse |
| AI 集成 | 可配置的 LLM 与 Embedding Provider |

## 项目结构

```text
TaoHybridRAG/
├── src/main/java/com/yizhaoqi/smartpai/
│   ├── client/          # 外部 AI 与服务客户端
│   ├── config/          # 安全与应用配置
│   ├── consumer/        # Kafka 消费者
│   ├── controller/      # REST 接口
│   ├── entity/          # 持久化实体
│   ├── handler/         # WebSocket 处理器
│   ├── repository/      # 数据访问层
│   └── service/         # 文档、检索、聊天与 Graph 服务
├── frontend/            # Vue 3 前端
├── docs/                # 部署、评测与基准测试文档
└── scripts/             # 可复现的本地工具
```

## 前置环境

- Java 17
- Maven 3.9+
- Node.js 18.20+
- pnpm 8.7+
- Docker 与 Docker Compose，或者自行准备 MySQL、Redis、Kafka、MinIO 和 Elasticsearch
- 与项目配置兼容的 LLM API 和 Embedding API

项目提供的隔离 Compose 配置会为 Elasticsearch 分配约 1 GiB 堆内存，启动全部服务前请确认 Docker 有足够资源。

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/liangbantaozi/taohybrid-rag.git
cd taohybrid-rag
```

### 2. 准备应用配置

将公开模板复制为本地 `.env`：

```powershell
Copy-Item .env.example .env
```

Linux 或 macOS：

```bash
cp .env.example .env
```

启动前至少需要检查并配置：基础服务连接信息、新生成的 `JWT_SECRET_KEY`、管理员初始化策略、LLM 与 Embedding Provider、跨域来源和注册模式。

`.env.example` 中的值仅用于本地开发示例。面向公网部署时不能继续使用示例密码或密钥。本地 `.env` 已被 Git 忽略。

### 3. 启动隔离的基础服务

隔离 Compose 需要四个密码。可以只在当前终端中设置，或者写入自己的、已被 Git 忽略的环境文件：

```powershell
$env:TAO_HYBRID_MYSQL_ROOT_PASSWORD = '<请设置本地密码>'
$env:TAO_HYBRID_REDIS_PASSWORD = '<请设置本地密码>'
$env:TAO_HYBRID_MINIO_ROOT_PASSWORD = '<请设置本地密码>'
$env:TAO_HYBRID_ELASTIC_PASSWORD = '<请设置本地密码>'

docker compose -f .\docs\docker-compose.tao-hybrid.yaml -p tao-hybrid up -d
```

Compose 对外暴露的端口如下：

| 服务 | 宿主机端口 |
| --- | ---: |
| MySQL | 3307 |
| Redis | 6380 |
| Kafka | 19092 |
| MinIO API / Console | 29000 / 29001 |
| Elasticsearch | 9201 |

需要同步修改 `.env` 中对应的端口。该 Compose 中的 Elasticsearch 使用 `http`，不是 `https`；应用侧密码必须与传给 Compose 的四个密码一致。

检查服务状态：

```powershell
docker compose -f .\docs\docker-compose.tao-hybrid.yaml -p tao-hybrid ps
```

### 4. 启动后端

```powershell
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8082`，并通过项目环境处理器读取根目录 `.env`。

### 5. 启动前端

```powershell
Set-Location frontend
pnpm install
pnpm dev
```

浏览器访问 `http://localhost:9528`。Git 中的开发环境配置会将 API 请求代理到 `http://localhost:8082/api/v1`。

### 6. 初始化第一个管理员

空数据库首次启动时，在 `.env` 中临时启用管理员初始化配置；后端成功创建管理员并完成登录后，应再次关闭初始化。公网环境不能长期保持初始化功能开启。

## 基础验证

1. 登录并上传一个较小的 TXT、HTML 或 PDF 文件。
2. 等待解析和向量化状态变为完成。
3. 提问一个能在该文档中找到答案的问题。
4. 确认回答包含来源引用，并能打开来源预览。
5. 关闭知识图谱参考，确认普通 Hybrid 检索仍然正常。
6. 若为部分文件启用了 Graph，单独验证其构建状态和权限范围。

## 开发检查

```bash
# 后端编译与测试
mvn -q -DskipTests compile
mvn test

# 前端类型检查与代码检查
cd frontend
pnpm typecheck
pnpm lint
```

## 评测支持

项目包含 BM25、向量、Hybrid 和可选 Graph 检索策略的评测代码，以及上传基准测试工具。评测结果会受到语料、模型配置、硬件、网络和服务环境影响，因此首个公开版本不发布本地测量指标或自动生成的结果文件。使用者可以在自己的环境中运行评测，并在报告结果时同时注明数据集、参数和运行条件。

## GraphRAG 当前状态

GraphRAG 当前是实验性、按需启用的补充能力：

- 管理员按文件决定是否构建 Graph。
- 实体、提及和关系保留来源文件/chunk 及权限 metadata。
- 查询路由过程可观测，并具有专项评测字段。
- Graph 超时、异常或无有效结果时会降级为普通 Hybrid 流程。
- 当前规则抽取在中文实体边界、同义词归一化和共现关系噪声方面仍有限制。

后续计划是在完整保留 Hybrid TopK 主证据的基础上，追加少量经过权限过滤的 Graph 补充证据，而不是让 Graph 结果挤掉主检索证据。

## 安全说明

- 不要提交 `.env`、模型密钥、真实用户文件、聊天导出、数据库备份或生产日志。
- 任何服务暴露到公网前都必须替换开发凭据。
- 生产环境应限制允许的跨域来源和用户注册策略。
- MySQL、Redis、Kafka、MinIO 和 Elasticsearch 应放在合适的网络边界内。
- 评测语料进入 Git 前需要确认公开授权和个人信息处理情况。

## 已知限制

- Kafka Compose 镜像当前使用 `latest` 标签，完整可复现发布前应固定具体版本。
- LiteParse 是可选能力，需要单独准备本地命令或运行环境。
- 第一版检索数据集规模较小且关键词明显，困难集和跨文档结果需要单独报告。
- Graph 抽取目前基于规则，仍属于实验功能。
- 生产部署仍需要结合实际环境补充安全加固、备份、监控和资源规划。

## 开源协议

本项目使用 [Apache License 2.0](LICENSE)。
