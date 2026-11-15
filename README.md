# Ticknova

**Ticknova 是面向单组织、本地部署的 AI 工单与知识协同平台。** 它将服务请求、经过独立审核的知识和带来源的 AI 建议连接起来，帮助服务团队处理问题、沉淀经验。

[![CI](https://github.com/kevin-sanas/Ticknova/actions/workflows/verify.yaml/badge.svg)](https://github.com/kevin-sanas/Ticknova/actions/workflows/verify.yaml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 功能

- **服务工单**：创建、检索、领取和分派工单；支持公开回复、内部备注、状态流转、乐观锁和幂等请求。
- **知识协作**：按空间和分类管理知识；上传 Markdown、TXT、PDF、DOCX，经过解析、独立审核和发布后供有权限的成员阅读。
- **知识助手**：结合全文与向量检索，在授权范围内查找知识片段并提供引用；证据不足时拒答。
- **AI 辅助处理**：提供工单分类、摘要和回复建议；建议由人员确认，回复仍需手动发送。
- **运营与管理**：提供运营分析、用户角色、队列、服务目录和审计记录。

## 架构

~~~mermaid
flowchart LR
    U[浏览器] --> W[Vue 3 + TypeScript]
    W --> N[Nginx 同源代理]
    N --> A[Spring Boot API]
    A --> DB[(PostgreSQL + pgvector)]
    A --> F[(私有文件卷)]
    A --> D[FastAPI + Docling 文档解析]
    A -. 授权内容 .-> M[本地 mock 或兼容模型服务]
~~~

后端使用 Java 21、Spring Boot、Spring Security 和 Flyway；前端使用 Vue、Pinia、Vue Router、Element Plus 和 Tiptap。数据库迁移文件位于 `db/migration/`。

## 快速开始

### 环境要求

- Docker Desktop（Linux 容器模式）或 Docker Engine，以及 Docker Compose。
- 建议至少 8 GiB 可用内存；首次构建需要访问镜像和依赖源。
- 默认本地端口为 `5173`（Web）和 `8080`（API）。

获取项目：

~~~sh
git clone https://github.com/kevin-sanas/Ticknova.git
cd Ticknova
~~~

在仓库根目录执行：

~~~powershell
# Windows PowerShell
.\scripts\Start-Local.ps1
~~~

~~~sh
# Linux / macOS
sh scripts/Start-Local.sh
~~~

启动后访问 <http://127.0.0.1:5173>。脚本使用独立的 `ticknova-demo` 环境，生成本地配置并在空库中初始化原创示例数据；非空数据库不会被覆盖。Windows 完整启动和 Linux 容器内相关检查已有验证，原生 Linux/macOS 从零启动仍需验证。

演示账号为 `admin`、`agent`、`requester`、`editor` 和 `reviewer`。初始化后，各账号密码保存在本地忽略文件 `.env.demo` 的 `DEMO_<用户名大写>_PASSWORD` 项中；密码不会进入仓库。已有环境应继续使用其原有账号密码。

默认使用 **mock 模型**，适合离线演示和回归，不代表真实模型效果。外部模型配置、数据发送范围与成本须由部署者确认，参见 [部署与运维](docs/11-本地部署运维与CI-CD.md)。

## 角色与权限

| 角色 | 主要职责 |
| --- | --- |
| `REQUESTER` | 提交和跟进自己的工单，阅读获授权的已发布知识 |
| `AGENT` | 在所属队列权限范围内读取和处理工单 |
| `KB_EDITOR` | 在获授权空间创作、上传、提交审核和发布知识 |
| `KB_REVIEWER` | 在获授权空间审核他人提交的版本 |
| `ADMIN` | 管理用户、角色、空间成员及系统配置 |

角色不隐式继承。知识正文还受空间权限约束；作者不能审核自己的版本，管理员也不会自动获得所有空间的内容访问权。详见 [角色与需求](docs/02-角色用例与需求.md) 和 [安全与权限](docs/09-安全隐私与权限.md)。

## 测试与验证

前端检查：

~~~sh
cd frontend
npm ci --ignore-scripts
npm run typecheck
npm run lint
npm test
npm run build
~~~

后端和浏览器集成测试使用独立测试数据库和容器；具体命令、依赖以及数据隔离要求见 [测试说明](docs/12-测试矩阵与阶段门.md)。持续集成执行后端、前端、解析服务、脚本检查及 Compose 镜像构建，实际结果以 [GitHub Actions](https://github.com/kevin-sanas/Ticknova/actions/workflows/verify.yaml) 为准。

## 评测与边界

仓库提供原创服务台语料、RAG 评测入口和原始结果。真实模型评测使用开发者候选标签，人工相关性及 groundedness 审核尚未完成；候选标签匹配不能等同于事实正确性。模型、数据集与测量限制见 [RAG 设计与评测](docs/10-RAG设计与评测.md)。

本地 mock 基础负载曾在 Docker Desktop Linux 环境下测得工单列表 P95 38.97 ms、工单创建 P95 39.60 ms；数据规模、并发和其他限制见 [原始报告](docs/evidence/performance-mock-20261024T121400557Z.json)。这些数值不是生产性能承诺，也不代表真实模型延迟。

项目提供本地备份和隔离恢复工具，但生产发布、跨机容灾及真实模型质量仍需根据目标环境单独验收。依赖许可及公告记录见 [许可证与接口边界](docs/15-开源许可证与接口边界.md)。

## 文档与贡献

- [产品目标与范围](docs/01-项目章程与PRD.md)
- [架构与技术选型](docs/05-架构与技术选型.md) · [数据模型](docs/06-数据模型ERD与SQL.md) · [API 契约](docs/07-API契约.md)
- [部署与运维](docs/11-本地部署运维与CI-CD.md) · [测试说明](docs/12-测试矩阵与阶段门.md)
- [开发与贡献指南](docs/开发流程说明.md) · [前端设计系统](docs/frontend/UI_DESIGN_SYSTEM.md)

## 许可证

Ticknova 项目代码以 [MIT License](LICENSE) 发布。第三方依赖、图标和其他资源仍按其各自的许可证使用；相关声明见各模块中的 `THIRD-PARTY-NOTICES.txt`。
