# 11｜本地部署、运维与 CI/CD

## 阶段 A：干净演示与测试隔离（2026-10-24，当前入口）

`scripts/Start-Local.ps1` 现在使用 `.env.demo`、`compose.yaml + compose.demo.yaml` 和 `ticknova-demo` 项目，数据库为 `ticknova_demo`，卷为 `ticknova-demo_postgres_data` / `ticknova-demo_uploads`；默认只使用 mock。旧 `ticknova` 应用容器停止，原 `.env`、`ticknova_postgres_data` 和 `ticknova_uploads` 保留。下面各历史章节中的 `.env` / `ticknova` 指旧入口，不应混用于新演示库。

`scripts/Seed-Demo.ps1` 校验数据库身份、空业务库及原始四账号，再通过现有登录/首次改密、目录、工单、知识创作、Docling、独立审核、发布和索引接口准备原创样例。密码只写 `.env.demo`；完成后记录 `DEMO_CONTENT_SEEDED` 事件。已完成的种子重复执行不写业务或审计；非空且无完成事件时保留数据并报错，避免把中断的初始化误报为成功。当前种子为五个账号、四队列、八服务、十二篇知识、二十四张工单，全部为虚构演示内容。

`scripts/Test-Browser.ps1` 自建 `ticknova-browser` 项目、`ticknova_browser` tmpfs 库和一次性 `.env.browser`，全入口先验证 Nginx 的 `browser-test` 标记，禁止连接演示入口。它使用独立于后端 `ticknova-test` 的容器和网络；每个浏览器套件之间重启专用 API 清空内存登录配额，保持产品登录限流配置不变。结束后清理测试栈和凭据；截图/JSON 只进入系统临时目录，默认清理，人工检查使用 `-KeepArtifacts` 后及时删除。`-Scope Demo` 在测试库运行同一原创样例，不向演示库写浏览器合成夹具。

默认完整构建。官方 Python 镜像元数据访问失败而本地解析镜像可用时，启动和浏览器脚本可加 `-SkipParserBuild`；`Assert-ParserImage.ps1` 必须先核对 app.py、worker.py、limits.py、uv.lock、pyproject.toml 五份 SHA-256，任一不同即拒绝复用。

备份与隔离恢复默认使用新 `.env.demo`；操作旧库时显式加 `-Legacy`。切回旧环境只需先停止新演示栈，再执行 `docker compose --env-file .env -p ticknova -f deploy/compose.yaml start`，使用保留的原容器及原卷。切换前先记录当前正在使用的环境，不删除任何演示卷。

## G5 本地备份与隔离恢复（2026-10-23）

运行 `.\scripts\Backup-Local.ps1` 时，现有 API 若正在运行会先停写，PostgreSQL 保持运行；脚本生成 `pg_dump` 自定义格式快照并从 API 私有卷复制原件，逐一核对数据库 `storage_key` 与 SHA-256，最后写入 manifest。脚本结束会恢复 API/数据库原先的运行状态；若恢复服务失败会明确报错。备份位于项目已忽略的 `data/backups`，包含账号与文档等敏感数据，不能提交仓库。

运行 `.\scripts\Test-Restore.ps1 -BackupDirectory '<项目内 data/backups 的备份目录>'` 可先验证清单及文件 hash，再在独立 Compose 项目中用 tmpfs PostgreSQL 恢复 dump、校验 Flyway 版本和全部文件引用，启动只读挂载原件的 API 检查 readiness 和可读 hash。测试容器不映射宿主端口，结束后移除临时库；**不会覆盖现有演示数据库或原件**。这是本地一致性备份和恢复演练，不等于生产热备、跨机容灾或 RPO/RTO 承诺。

失败索引的重试由有空间编辑权的用户在知识版本历史中发起；审计记录旧失败码。解析超时现在区分响应头等待超时与其他传输故障；解析服务若响应头已经发送，超时会终止传输而不再次写入 504 响应头。真实模型、生产发布及整体供应链门仍需单独验收。

## Ticknova V2 本地升级（2026-10-21）

启动入口仍为 `scripts/Start-Local.ps1` / `http://127.0.0.1:5173`，不重置现有密码或数据。V5/V6由API启动时Flyway增量执行；本次V4→V6原账号密码/状态、工单、文档、版本原件和发布指针核验保持一致，见 `evidence/ui-v2-demo-upgrade.json`。产品名称、页面品牌、容器、数据库与工程标识统一为Ticknova。

新增浏览器设计验收使用五种宽度及真实本地API；具体命令和结果见第12篇。仍只向127.0.0.1暴露web/API，不发布生产环境或远端。备份恢复、失败索引管理及既有解析超时遗留项仍属G5，未因UI整改而视为完成。

## G0–G3 已落地入口

当前运行 postgres/api/web/docling；一条 `scripts/Start-Local.ps1` 生成本地随机配置并构建启动项目专用服务。脚本保留已有 `.env` 和数据卷，补齐缺失的解析服务随机令牌；数据库仅在容器网络中可访问。`scripts/Test-Backend.ps1` 使用 `ticknova-test` 网络、独立 `ticknova_test` 数据库和 tmpfs，并调用真实解析服务；正式数据不参与测试。

原件保存在 `uploads` 私有命名卷，API 以非 root 用户访问；Docling 只有内部网络和 bearer 入口，不发布端口。解析容器只读根文件系统、临时目录 tmpfs 256 MiB、1 CPU/1 GiB、128 PID、丢弃 capabilities、禁止提权；每次解析使用独立子进程并回收。当前不下载模型、不需要外部模型密钥。worker 的 120 秒租约和 fencing token 保障进程中断后可恢复，最多自动尝试五次；终止状态可手动重试并保留旧 job 证据。

宿主 Docker Hub 鉴权连接超时时，可在项目 `.env` 设置 `DOCLING_PYTHON_IMAGE` 指向同一已核验 digest 的镜像代理；默认仍为官方固定镜像，不修改 Docker 全局配置。实际本机使用的代理及 digest 记录在第16篇。

Maven 官方镜像的 `MAVEN_CONFIG` 表示路径，与经典 Wrapper 的同名 CLI 参数约定不同；容器运行 Wrapper 前显式清除此变量。采用 bin 类型 Wrapper，通过 Java 解压并验证 zip 摘要，避免缺少 unzip 时自动改为 tar 包导致校验错配。

基础镜像均固定版本和 digest；配置见 `deploy/compose.yaml`、`compose.test.yaml` 和 Dockerfile。Java API 镜像以非 root 用户运行；本轮运行时使用固定 Maven/JDK 基础镜像，体积优化留到交付打磨。CI 工作流已编写，实际远端运行仍为 NOT RUN。本轮备份恢复、压力测试、模型故障降级尚未验收。

## 本地运行设计

前提：Docker Compose、约 8 GiB 可用内存、可访问镜像/依赖源。Compose 服务：`postgres`（PostgreSQL 17 + pgvector）、`api`（Spring Boot）、`web`（Vue 静态站点/API 代理）、`docling`（FastAPI）。基础流程无需独立 Redis、Kafka、RabbitMQ、对象存储或外部模型密钥。后续 OCR 若引入模型，必须先复核模型权利和资源需求。

交付时应提供 `.env.example` 和迁移初始化数据；正式 `.env` 不进 Git。预期开发者体验：复制 env 文件、填写随机本地密钥、执行 `docker compose up --build`、访问 `http://localhost:5173`（具体端口在实现时统一）。健康检查成功后用 README 演示账户登录，强制启动时更改种子密码。

## 环境变量契约

| 变量 | 默认/说明 | 敏感 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 否 |
| `DB_URL/DB_USER/DB_PASSWORD` | Compose 内网数据库 | 密码是 |
| `SESSION_TOKEN_PEPPER` | 本地随机生成的 token hash pepper | 是 |
| `AI_PROVIDER` | `mock`；显式`openai`使用OpenAI兼容适配器 | 否 |
| `AI_BASE_URL/AI_API_KEY/AI_MODEL` | 真实模型配置；只在服务端读取 | API key 是 |
| `EMBEDDING_MODEL` | 真实模式显式指定模型；维度在代码固定1024并逐响应校验 | 否 |
| `AI_WORKER_ENABLED` | 默认true，控制G4索引/生成调度，测试false | 否 |
| `DOCLING_BASE_URL` | Compose 内网地址 | 否 |
| `DOCLING_INTERNAL_TOKEN` | 脚本生成的内部 bearer 凭据 | 是 |
| `DOCLING_PYTHON_IMAGE` | 可选构建来源，须固定并核验同一 digest | 否 |
| `UPLOAD_ROOT/MAX_UPLOAD_BYTES/MAX_PDF_PAGES` | 本地受限目录、20 MiB、100 页 | 否 |
| `JOB_MAX_ATTEMPTS` | 5 | 否 |

G4默认不需要额外env值，mock不会读取真实密钥或调用第三方。真实模式须同时设置AI_PROVIDER/openai、AI_BASE_URL、AI_API_KEY、AI_MODEL、EMBEDDING_MODEL；配置无效会明确报错，运行期provider故障由任务有限重试处理。更换embedding模型/地址产生新profile，旧向量不参与新查询，后台分批重建。调度池3线程，解析/索引/生成不互相独占单线程。`JOB_MAX_ATTEMPTS`映射G3知识worker上限（1–5）；G4任务同样有限尝试。`MAX_UPLOAD_BYTES`/`MAX_PDF_PAGES`只允许收紧20MiB/100页硬上限。Micrometer已注册G3指标，HTTP仅开放health，未交付采集端点或告警。

## 运维动作

- 探针：`/actuator/health/readiness` 和 liveness 分开；readiness 检查数据库与迁移，不因外部模型短暂不可用让全部核心工单 API 不健康。
- 备份：`pg_dump` + 文件目录一致性备份；记录恢复时间和版本；恢复后校验文件 hash、版本和向量重建路径。
- 迁移：向前兼容、先加后删；变更 embedding 维度以新版本索引迁移。
- 日志：JSON、`traceId`、job ID、模块；文本字段脱敏；设置大小轮转。
- 关停：worker 先停止领取新任务，等待租约任务完成；未完成任务可安全重领。
- 故障：模型不可用时允许工单核心流程继续；Docling 故障时上传显示待处理/可重试；数据库故障时只读提示，不谎报写入成功。

## CI/CD 建议

GitHub Actions 或同类 CI：后端编译与单元测试、迁移测试、前端 lint/typecheck/build、Python lint/契约测试、Compose 镜像构建、依赖/许可证/SBOM 扫描。主分支只部署演示环境；生产发布不在本包授权范围。CI 上传实际测试摘要和依赖清单，不把 API 密钥作为构建参数。
