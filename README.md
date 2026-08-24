# TaoHybirdRAG

TaoHybirdRAG 是面向企业知识库问答的 Java RAG 项目，提供文档上传、异步解析、向量化、权限过滤、混合检索、ReAct 工具调用和带引用的流式对话能力。

核心技术栈包括 Spring Boot 3.4.2、Java 17、Vue 3.5、Elasticsearch 8.10、Kafka、WebSocket、MySQL、Redis、MinIO、Apache Tika、LiteParse 与可配置的 LLM/Embedding Provider。

它的目标是帮助企业和个人更高效地管理和利用知识库中的信息，支持多租户架构，允许用户通过自然语言查询知识库，并获得基于自身文档的 AI 生成响应。

系统允许用户：

- 上传和管理各种类型的文档
- 自动处理和索引文档内容
- 使用自然语言查询知识库
- 接收基于自身文档的 AI 生成响应

用到的技术栈包括，先说后端的：

+ 框架 : Spring Boot 3.4.2 (Java 17)
+ 数据库 : MySQL 8.4.10
+ ORM : Spring Data JPA
+ 缓存 : Redis
+ 搜索引擎 : Elasticsearch 8.10.4
+ 消息队列 : Apache Kafka
+ 文件存储 : MinIO
+ 文档解析 : Apache Tika 2.9.1 + LiteParse
+ 安全认证 : Spring Security + JWT
+ AI集成 : 可配置的 DeepSeek LLM 与 DashScope Embedding
+ 实时通信 : WebSocket
+ 依赖管理 : Maven
+ 响应式编程 : WebFlux

后端的整体项目结构：

```bash
src/main/java/com/yizhaoqi/smartpai/
├── TaoHybridRagApplication.java   # 当前主应用程序入口
├── client/                       # 外部API客户端
├── config/                       # 配置类
├── consumer/                     # Kafka消费者
├── controller/                   # REST API端点
├── entity/                       # 数据实体
├── exception/                    # 自定义异常
├── handler/                      # WebSocket处理器
├── model/                        # 领域模型
├── repository/                   # 数据访问层
├── service/                      # 业务逻辑
└── utils/                        # 工具类
```

再说前端的，包括：

+ 框架 : Vue 3 + TypeScript
+ 构建工具 : Vite
+ UI组件 : Naive UI
+ 状态管理 : Pinia
+ 路由 : Vue Router
+ 样式 : UnoCSS + SCSS
+ 图标 : Iconify
+ 包管理 : pnpm

前端的整体项目结构：

```bash
frontend/
├── packages/           # 可重用模块
├── public/             # 静态资源
├── src/                # 主应用程序代码
│   ├── assets/         # SVG图标，图片
│   ├── components/     # Vue组件
│   ├── layouts/        # 页面布局
│   ├── router/         # 路由配置
│   ├── service/        # API集成
│   ├── store/          # 状态管理
│   ├── views/          # 页面组件
│   └── ...            # 其他工具和配置
└── ...               # 构建配置文件
```



## 核心功能

### 知识库管理

TaoHybirdRAG 支持文档上传、异步解析和组织标签管理。文档可设为公开或私有，并按用户与组织标签进行权限过滤。

### AI驱动的RAG实现

TaoHybirdRAG 的 RAG 链路包括：

- 将上传的文档进行语义分块
- 调用可配置的 Embedding 模型为每个文本块生成向量
- 将向量存储到 Elasticsearch，以支持语义搜索和关键词检索
- 可以根据用户的查询检索相关文档
- 为 LLM 提供完整的上下文，从而生成更准确、基于文档的响应内容

### 企业级多租户

TaoHybirdRAG 通过组织标签实现多租户隔离。用户可创建或加入多个组织，检索时结合用户、公开状态和组织标签过滤文档。

### 实时通信

系统采用 WebSocket 技术，提供用户与 AI 系统之间的实时交互，支持响应式聊天界面，便于知识检索和 AI 互动。

## 前置环境

在开始之前，请确保已安装以下软件：

- Java 17
- Maven 3.9.6
- Node.js 18.20.0 或更高版本
- pnpm 8.7.0 或更高版本
- MySQL 8.4.10
- Elasticsearch 8.10.4
- MinIO RELEASE.2025-04-22T22-12-26Z
- Kafka（当前镜像标签为 `bitnamilegacy/kafka:latest`）
- Redis 7.4.11
- Docker（可选，用于运行 Redis、MinIO、Elasticsearch 和 Kafka 等服务）

## 架构设计

TaoHybirdRAG 采用分层后端、异步文档处理和独立检索服务组合，当前重点演进可评测的混合检索、重排、问题改写和数据处理质量。

控制层用于处理 HTTP 请求，验证输入，管理请求/响应格式化，并将业务逻辑委托给服务层。控制器按领域功能组织。遵循 RESTful 设计原则，集成了性能监控和日志记录，用于跟踪 API 使用和故障排除。

```java
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        // 参数验证和委托给服务
        documentService.deleteDocument(fileMd5);
        // 响应处理
    }
}
```

服务层主要用来处理应用的业务逻辑，具有事务感知能力，能够处理跨越多个数据源的操作。

```java
@Service
public class DocumentService {
    @Autowired
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private ElasticsearchService elasticsearchService;
    
    @Transactional
    public void deleteDocument(String fileMd5) {
        // 文档删除的业务逻辑
        // 协调多个仓储和系统
    }
}
```

数据访问层使用 Spring Data JPA 进行数据库操作，提供了对 MySQL 的 CRUD 操作。

```java
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
}
```

实体层由映射到数据库表的 JPA 实体以及用于 API 请求和响应的 DTO（数据传输对象）组成。

```java
@Entity
public class FileUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String fileMd5;
    private String fileName;
    private String userId;
    private boolean isPublic;
    private String orgTag;
    // 其他字段和方法
}
```

## 环境变量与新的启动方式

现在本地开发建议按“准备 `.env` -> 启基础服务 -> 启后端 -> 启前端”的顺序启动，不再需要把一堆环境变量手动 export 到终端。

### 1. 准备项目根目录 `.env`

项目根目录的 `.env` 用于保存后端本地运行和部署相关配置。首次使用时先复制模板：

```bash
cp .env.example .env
```

后端启动时会通过 `DotenvEnvironmentPostProcessor` 自动读取项目根目录 `.env`。可在 IDE 中运行后端启动类，或在项目根目录使用项目内 Maven 启动。

`.env` 里当前主要有三类配置：

- 应用运行配置：MySQL、Redis、Kafka、MinIO、Elasticsearch、JWT、AI Provider 等
- 初始化与安全配置：如 `ADMIN_BOOTSTRAP_*`、`APP_AUTH_REGISTRATION_MODE`、`SECURITY_ALLOWED_ORIGINS`
- 前端部署配置：如 `DEPLOY_SERVER_HOST`、`DEPLOY_SERVER_USER`、`DEPLOY_SERVER_KEY`、`DEPLOY_TARGET_DIR`、`DEPLOY_HEALTHCHECK_URL`

几个关键项建议优先确认：

- `SPRING_PROFILES_ACTIVE=dev`：本地源码启动默认使用 `dev`
- `SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`：数据库和 Redis 连接
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`、`MINIO_*`、`ELASTICSEARCH_*`：基础依赖地址
- `JWT_SECRET_KEY`：必须是 Base64 字符串，可用 `openssl rand -base64 32` 生成
- `ADMIN_BOOTSTRAP_ENABLED`：仅首次创建管理员时临时改为 `true`，创建完成后改回 `false`

说明：

- `.env` 是后端和部署脚本共用的根配置
- 前端自己的 Vite 变量仍放在 `frontend/.env`、`frontend/.env.test`、`frontend/.env.prod`
- `pnpm run dev` 实际使用的是 `vite --mode test`，默认会读取 `frontend/.env.test`

### 2. 启动本地基础服务

使用独立 Docker Compose 启动基础服务：

```powershell
cd D:\codextest\TaoHybirdRAG
docker compose --env-file .\docs\.env.tao-hybird -f .\docs\docker-compose.tao-hybird.yaml -p tao-hybird up -d
```

### 3. 启动后端

基础服务就绪后，在项目根目录启动 Spring Boot：

```powershell
$env:JAVA_HOME = (Resolve-Path .\.local-tools\jdk-17.0.19+10).Path
& .\.local-tools\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
```

也可以直接在 IDE 中运行后端启动类，效果一样，都会自动读取根目录 `.env`。

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm run dev
```

前端开发服务默认运行在 `http://localhost:9528`，后端运行在 `http://localhost:8082`；代理配置位于 `frontend/.env.test`。

### 5. 服务器脚本启动

如果是服务器上用 jar 包方式运行，可以参考根目录的 `launch.sh.example`。建议先复制成你自己的启动脚本，再按需调整 JDK、Maven 和 jar 名称。脚本支持先加载指定 `.env`，再执行 `start`、`restart`、`stop`、`status`、`logs` 等命令。

```bash
cp launch.sh.example launch.sh
chmod +x launch.sh

# 使用默认 .env 启动
./launch.sh start

# 使用指定环境文件启动
./launch.sh start -e .env.prod
```

其中：

- `start`：会先 `git pull`，再重新打包并启动
- `restart`：直接重启现有 jar
- `status` / `logs`：查看进程状态和日志

### 6. 前端部署脚本

`deploy-front.sh` 用于构建前端、打 zip 包并上传到服务器。生产部署前应在 `.env` 中配置独立的目标目录与健康检查地址。

```bash
# 直接构建并部署前端
./deploy-front.sh
```

部署脚本默认会执行这些步骤：

- 进入 `frontend` 执行 `pnpm build`
- 打包 `dist` 为 zip 文件并上传到服务器
- 删除远端旧的 `dist` 目录并解压新包
- 检查远端 `dist/index.html` 是否存在
- 请求 `DEPLOY_HEALTHCHECK_URL` 做健康检查

如果只想复用已有的前端构建产物，可以在执行时跳过构建：

```bash
DEPLOY_SKIP_BUILD=1 ./deploy-front.sh
```
