# 11｜本地部署、运维与 CI/CD

Ticknova 的本地栈包含 PostgreSQL 17 + pgvector、Spring Boot API、Vue 静态站点和 Docling 解析服务。演示使用独立数据库与本地 mock；本页介绍本地运行与验证，不构成生产部署方案。

## 准备与启动

需要 Docker Engine 或 Docker Desktop 的 Linux 容器引擎及 Docker Compose，建议至少 8 GiB 可用内存。首次构建需要访问配置的镜像和依赖来源；宿主机不必安装 Java、Node 或 Python。

在项目根目录执行：

```powershell
# Windows PowerShell
.\scripts\Start-Local.ps1
```

```sh
# Linux / macOS
sh scripts/Start-Local.sh
```

入口默认是 <http://127.0.0.1:5173>，API 默认绑定 `127.0.0.1:8080`。两个启动入口均使用 `ticknova-demo` 项目、`.env.demo` 和独立的数据库及上传卷，演示模型固定为 mock。脚本生成本地随机配置、构建服务，并通过现有 API 完成空库演示数据初始化；已完成的种子不会重复写入，非空且缺少完成标记时会停止，不覆盖既有业务数据。五个演示账号及初始数据范围见 [README](../README.md)。

两种种子入口不支持并发运行。中断后如需处理遗留锁，应先确认没有初始化进程正在运行。Windows 完整启动与 Linux 容器内 Shell 检查有历史验证；原生 Linux/macOS 从零完整启动尚未实测。

停止演示服务而保留数据：

```sh
docker compose --env-file .env.demo -p ticknova-demo -f deploy/compose.yaml -f deploy/compose.demo.yaml stop
```

数据库和上传文件保存在命名卷中；不要使用带 `--volumes` 的清理命令处理需要保留的数据。其他 Compose 项目若使用相同的 5173 或 8080 端口，需先停止占用端口的服务或配置不同端口。

## 配置与数据边界

`.env.example` 仅列出配置项；实际密钥保存在本机被 Git 忽略的环境文件中，不要提交、复制进文档或写入日志。演示账号当前密码由启动和种子脚本保存在 `.env.demo` 对应的 `DEMO_<用户名大写>_PASSWORD` 项中。

| 配置项 | 用途 |
|---|---|
| `DB_PASSWORD`、`SESSION_TOKEN_PEPPER` | 数据库密码和会话令牌摘要 pepper，由本地配置生成 |
| `DEMO_INITIAL_PASSWORD`、`DEMO_SEED_ENABLED` | 初始种子账号密码与初始化开关 |
| `DOCLING_INTERNAL_TOKEN` | API 到解析服务的内部令牌 |
| `WEB_PORT`、`API_PORT` | 本地 Web 和 API 端口，默认分别为 5173、8080 |
| `MAX_UPLOAD_BYTES`、`MAX_PDF_PAGES` | 文件大小和 PDF 页数限制，不能超过 20 MiB、100 页的硬上限 |
| `AI_PROVIDER`、`AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` | 模型模式与真实模型服务配置 |
| `EMBEDDING_MODEL`、`EMBEDDING_BATCH_SIZE` | 向量模型及批量大小；向量维度固定为 1024，批量大小范围 1–32 |
| `AI_WORKER_ENABLED` | 控制 AI 索引和生成任务调度 |
| `DOCLING_PYTHON_IMAGE` | 可选的解析镜像构建来源，使用时应核验固定 digest |

PostgreSQL 和解析服务默认仅处于容器网络；原件保存在私有上传卷。API 暴露健康检查，解析服务有内部访问令牌。不要将示例配置直接当作生产安全配置。

## 真实模型与 RAG 评测

`mock` 模式用于本地演示和回归。显式配置 OpenAI 兼容的真实 provider 后，获授权的知识片段和工单公开内容可能发送给该服务；配置前应确认数据范围、服务条款和费用，并将密钥仅写入本地忽略文件。切换 embedding provider 或模型会生成新的索引 profile，不能把旧模型向量当成新模型结果。

评测使用单独的 `.env.eval`、`ticknova-eval` 项目和临时数据库：

```powershell
# 默认使用 mock
.\scripts\Run-RagEvaluation.ps1

# 确认模型、调用数量和费用后才启用真实模式
.\scripts\Run-RagEvaluation.ps1 -Real -AllowPaid
```

真实模式需在本地 `.env.eval` 配置 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` 和 `EMBEDDING_MODEL`。脚本默认最多 120 次聊天和 128 条 embedding 输入；这些是调用数量限制，不是费用硬上限，实际计费以 provider 为准。脚本会重置并清理固定的 `ticknova-eval` 测试项目及其临时资源；运行前先确认该项目没有需要保留的数据，且不要并发运行同一评测。

模型选择、指标定义、实测和局限见 [RAG 设计与评测](10-RAG设计与评测.md)。真实模型的质量不能由 mock 结果推断。

## 测试隔离与负载

后端测试使用 `ticknova-test`、独立测试数据库及解析服务；浏览器回归使用 `ticknova-browser`、一次性配置和临时库。`scripts/Test-Browser.ps1` 会重置固定的浏览器测试项目及其数据卷，并清理临时凭据和默认截图。运行前请确认该项目没有需要保留的数据；不要把浏览器测试指向演示或业务数据库。

`scripts/Run-Performance.ps1` 使用固定的 `ticknova-perf` 项目、独立临时数据库和 mock；脚本会重置对应测试项目。它记录工单列表、创建和异步问答的样本数、环境与延迟。异步耗时包含排队、检索、mock 生成和轮询，不能当作纯检索或真实模型延迟。历史实测和测试命令见[测试矩阵与阶段门](12-测试矩阵与阶段门.md)。

启动或浏览器脚本提供 `-SkipParserBuild` 选项，仅在本地解析镜像通过源码与依赖锁哈希校验后复用。完整测试应核对镜像、数据集及执行环境，避免与其他负载测试并行。

## 备份与恢复演练

PowerShell 脚本默认针对 `ticknova-demo`：

```powershell
.\scripts\Backup-Local.ps1
.\scripts\Test-Restore.ps1 -BackupDirectory '<项目内 data/backups 下的备份目录>'
```

备份脚本在 API 运行时短暂停写，使用 `pg_dump` 和上传文件制作快照，核验文档引用的 SHA-256，并尝试恢复原服务状态。备份存于被忽略的 `data/backups`，可能包含账号和文档内容，应妥善保管且不能提交仓库。针对使用 `.env` 的原始 `ticknova` 项目，可给两个脚本显式添加 `-Legacy`。

恢复脚本验证清单与文件哈希后，在独立项目和临时 PostgreSQL 中恢复并校验数据库版本、引用与 API 可读性；它是恢复演练，不覆盖现有数据库或上传卷，也不等于跨机灾备、生产热备或 RPO/RTO 承诺。

## CI 与发布限制

仓库的 [verify 工作流](../.github/workflows/verify.yaml)包含后端 PostgreSQL 集成测试、前端检查、脚本测试、解析服务测试和 Compose 镜像构建。特定提交的历史结果及未测项见[测试矩阵](12-测试矩阵与阶段门.md)；每次提交应查看对应运行结果。

生产发布仍需要结合目标环境核验账户与权限、网络和密钥、备份恢复、资源限制、日志监控、依赖和容器 OS 安全、真实模型质量及费用。现有本地演示与 CI 通过不表示这些项目已经验收。
