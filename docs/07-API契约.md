# 07｜REST API 契约

## G0–G2 实际实现与补齐

机器可读 OpenAPI 为 `GET /api/v1/openapi`（需要完成首次改密后的 bearer 会话）。以下路径均相对于 `/api/v1`。G3 知识摄取及本轮G4契约见下文；最终验收状态以第12篇实测为准。

## G4 问答与建议实施契约

| 方法与路径 | 请求与响应 | 权限 |
|---|---|---|
| `GET /ai/config` | `{mode,profile,externalProvider}`，不含密钥 | 已登录且完成首次改密 |
| `POST /ai/answers` | `{question,spaceIds}` → `202 {jobId}` | 当前可读空间；空选择允许无资料拒答 |
| `POST /tickets/{id}/ai-drafts` | `{kind,spaceIds}` → `202 {jobId}`；kind=REPLY/SUMMARY/CLASSIFICATION | 当前工单可公开回复者 |
| `GET /jobs/{id}` | AI：`{id,status,errorCode,attemptCount,result}`；G3摄取保持原DTO | 任务创建者，重新核对原空间/工单/引用；摄取继续按原空间ACL |
| `POST /ai/suggestions/{id}/feedback` | `{feedback:"ACCEPTED"\|"REJECTED",reason?}` →204 | 建议创建者及全部当前来源权限 |

两类创建要求1–128字符Idempotency-Key；同键不同内容409，重放重新鉴权。question为1–4000字符；显式spaceIds最多10个唯一UUID；空数组在创建时解析为当时全部可读空间并持久化，后续新增权限不扩大旧任务。反馈reason最多500字符，同选择重放无副作用，改选409。任务状态QUEUED/RUNNING/RETRYING/COMPLETED/FAILED；无证据为完成结果INSUFFICIENT_EVIDENCE，provider失败为稳定错误码。任何引用或原范围失效时，整个结果不展示，反馈返回权威403/404。

AI result形状：`{suggestionId,kind,mode,profile,outcome,answer,summary,serviceId,priority,tags,citations,requiresHumanReview:true,feedback}`；outcome为ANSWERED/INSUFFICIENT_EVIDENCE；citation为`{documentId,versionId,version,title,page,chunkId,snippet}`。page可空，snippet只含服务端核验的当前公开片段。摘要/分类可仅基于工单公开内容并无知识引用，界面必须说明；回答/回复草稿无可用来源则拒答。模型输入永不含内部备注。

REPLY的answer、SUMMARY的summary最多10,000字符，与公开回复共用后端校验常量；超长返回既有INSUFFICIENT_EVIDENCE结果，正文/摘要及引用清空，不截断。ANSWER保留既有16,000总输出预算。长度按Java/JavaScript一致的UTF-16单元计数；模型传输及JSON包体原有上限仍独立适用。

前端`/assistant`对所有登录角色可见。采纳回复/摘要只填公开编辑框，采纳分类只预填现有优先级/标签；服务项只展示。原有「发送回复/保存属性」仍是唯一业务提交动作，反馈接口不写工单。历史超长回复/摘要仍可查看或拒绝；尝试采纳时提示重新生成，不发送采纳反馈、不弹替换确认、不覆盖原稿。

| 接口 | 当前契约 |
|---|---|
| `POST /auth/change-password` | `{oldPassword,newPassword}`；204；撤销全部会话后重新登录 |
| `GET /users` / `GET /roles` | ADMIN；用户分页及四种角色代码 |
| `POST /users` | ADMIN；`{username,displayName,password,roles}`；201；强制首次改密 |
| `PATCH /users/{id}` | ADMIN；`{displayName?,active?}`；用户 DTO；停用立即撤销会话 |
| `PUT /users/{id}/roles` | ADMIN；`{roles}`；204；下一次请求重新加载角色，禁止移除最后一个活跃管理员 |
| `PATCH /queues/{id}` / `PATCH /services/{id}` | ADMIN；名称与启用状态；配置 DTO |
| `POST /services` | ADMIN；`{code,name}`；201 |
| `GET /queues/{id}/members` | ADMIN 或该队列 AGENT；`{userId,username,displayName,active,canProcess}[]` |
| `DELETE /queues/{id}/members/{userId}` | ADMIN；204；移除成员资格 |
| `PATCH /tickets/{id}` | 可处理队列 AGENT；`{priority?,tags?,version?}`；TicketSummary |
| `PUT /tickets/{id}/assignment` | 可处理源、目标队列 AGENT；`{queueId,assigneeId:null或UUID,version?}`；TicketSummary |
| `GET /audit-events` | ADMIN；分页，支持精确 `action` 筛选，计数与条目均过滤 |

所有分页形状统一为 `{items,total,page,size}`。`GET /queues` / `/services` 返回数组；管理员通过 `includeInactive=true` 查看停用服务。

工单详情形状为 `{ticket,description,resolutionNote,publicReplies,internalNotes,transitions,capabilities,allowedTransitions}`。`ticket` 包含编号、角色可见基本信息、版本与 `tags:string[]`；`capabilities` 包含 `canCommentPublic/canCommentInternal/canReadInternal/canAssign/canEdit`。只读队列成员可以读取内部备注，不能处理工单；请求者的 `internalNotes` 永远为空。详情在同一只读 REPEATABLE READ 快照中完成，避免跨队列移动期间混入新权限范围的内容。

修改支持 body `version` 或 `If-Match: "0"`；同时提供必须一致。缺少/格式错误返回 400，旧版本返回 409。创建、评论、状态转换必须携带 `Idempotency-Key`；同用户同操作同键重放不重复写入，重放重新检查资源权限。同键异内容与进行中请求返回 409。

状态理由公开可见，解决和已关闭工单的管理员重开必须填理由；请求者可对本人 `RESOLVED` 工单确认关闭或重开。`CLOSED` 重开要求 ADMIN + AGENT + 队列处理权限。当前工单不提供删除接口。

登录返回 `{accessToken,expiresIn,expiresAt,user}`，其中 `user` 含 `mustChangePassword`。受限首登会话只允许 `/me`、改密及登出。密码 12 字符起、UTF-8 最多 72 字节，用户名 3–80 字符；标签最多 10 个、各 1–32 字符。错误中包含 `code/traceId`，字段错误可含 `errors`。

## G3 知识摄取实现契约

| 接口 | 契约 |
|---|---|
| `GET /knowledge/spaces` | 数组 `{id,name,active,canRead,canEdit,canManageMembers}`；成员可见已授权空间；ADMIN 可见空间配置，但不因此获得正文权限 |
| `POST /knowledge/spaces` | KB_EDITOR/ADMIN，`{name}`；201；创建者获得读/编辑权限 |
| `GET /knowledge/spaces/{id}/members` / `member-candidates` | 创建者或 ADMIN；成员及启用账号候选（最多1000） |
| `PUT /knowledge/spaces/{id}/members/{userId}` | 创建者或 ADMIN；`{canRead,canEdit,reason}`，理由1–500字符；204；编辑必须同时可读且目标有 KB_EDITOR/ADMIN 角色；双 false 撤权 |
| `GET /knowledge/spaces/{id}/documents` | 可读空间，分页 `page/size/q`（标题），排除归档 |
| `POST /knowledge/spaces/{id}/documents` | 可编辑，multipart `title,file`；202 `{documentId,versionId,jobId}` |
| `POST /knowledge/documents/{id}/versions` | 可编辑，multipart `file`；202；同文档同 SHA-256 返回原版本和当前任务 |
| `GET /knowledge/documents/{id}` | 可读，基本元数据及 `versions`，不含原文；详情使用只读 REPEATABLE READ 快照。版本中的 `hasEligibleReviewer` 仅在当前用户可编辑时返回有效值：同空间存在另一位启用、具显式 KB_EDITOR 角色且同时可读可编辑的成员 |
| `GET /knowledge/documents/{id}/versions/{versionId}/chunks` | 可编辑者可预览；只读者只能读取当前已发布 READY 版本；数组 `{id,chunkIndex,pageNo,headingPath,content}`，纯文本 |
| `POST .../versions/{versionId}/publish` / `POST .../disable` | 可编辑，200 返回详情；READY 且有片段才能发布；撤回立即清空发布指针 |
| `POST .../versions/{versionId}/retry` | 可编辑；失败创建新任务保留旧失败证据，进行中重放当前任务；202 |
| `DELETE /knowledge/documents/{id}` | 可编辑；204；软归档并撤回，保留原文件/版本及审计证据 |
| `GET /jobs/{id}` | 重新检查对象空间 ACL；`{id,status,errorCode,attemptCount,versionId,documentId}`；当前只提供摄取任务 |

首次创建空间/文档可带 `Idempotency-Key`；页面自动提供并在相同输入重试时复用。键保留24小时，只保存资源ID与请求摘要；重放重新鉴权，同键不同标题/文件摘要等返回409。后续版本另有 `(document_id,sha256)` 唯一约束。不存在或不可访问的知识对象统一404；角色/可读对象内动作权限不足403。

文档详情基本字段：`id,spaceId,title,publicationState,latestVersionId,publishedVersionId,latestIngestState,versionCount,createdAt,canEdit`；版本字段：`id,versionNo,fileName,mimeType,sha256,ingestState,publicationState,errorCode,createdAt,jobId,jobStatus,attemptCount,chunkCount`。摄取状态 `QUEUED/EXTRACTING/CHUNKING/READY/FAILED`；任务状态 `QUEUED/RUNNING/RETRYING/COMPLETED/FAILED`。

上传路径/文件名无效400，格式/签名/MIME/UTF-8不匹配415，超文件上限413。PDF页数和深度内容校验在内部解析服务执行，失败任务返回稳定错误码（如 `PDF_PAGE_LIMIT/INVALID_DOCUMENT/NO_EXTRACTABLE_TEXT`）；不进入 SDK 转换或 READY 状态。原文件没有直接公开下载地址。

## 通用规则

- 浏览器 API 前缀 `/api/v1`；JSON UTF-8；认证采用短期 bearer token，内部服务使用单独服务凭据。
- 分页：`page` 从 0 开始，`size` 默认 20、最大 100；排序字段须白名单。
- 创建类操作支持 `Idempotency-Key`；修改工单支持 `If-Match: "<version>"` 或 body `version`，并发冲突返回 409。
- 时间使用 ISO-8601 UTC；ID 使用 UUID。
- 错误使用 Problem Details 形状：`{ "type":"about:blank", "title":"Conflict", "status":409, "code":"TICKET_STATE_CONFLICT", "traceId":"..." }`。不向客户端泄漏堆栈。

## 端点

| 方法与路径 | 权限 | 结果 |
|---|---|---|
| `POST /auth/login` | 匿名 | `200 {accessToken,expiresIn,user}` |
| `POST /auth/logout` | 登录 | 撤销当前不透明 bearer session，`204` |
| `GET /me` | 登录 | 当前用户和显式角色 |
| `GET /queues` | 登录 | 返回当前用户可访问队列 |
| `POST /queues` | ADMIN | 创建队列 |
| `PUT /queues/{id}/members/{userId}` | ADMIN | 授予/更新队列成员资格 |
| `GET /services` | 登录 | 启用服务目录 |
| `POST /tickets` | REQUESTER/AGENT | `201 TicketSummary`；必须幂等 |
| `GET /tickets` | 登录 | 按服务端权限过滤的分页列表 |
| `GET /tickets/{id}` | 本人或队列授权 | 工单详情；公开/内部评论按角色过滤 |
| `POST /tickets/{id}/comments` | 可访问工单 | `{visibility,body}`；REQUESTER 只能 `PUBLIC_REPLY` |
| `POST /tickets/{id}/transitions` | 请求者或 AGENT，按状态策略 | `{toStatus,reason,version}` |
| `POST /knowledge/spaces` | KB_EDITOR/ADMIN | 新知识空间 |
| `PUT /knowledge/spaces/{id}/members/{userId}` | ADMIN/空间管理员 | 设置 `canRead/canEdit`；审计变更者和理由 |
| `POST /knowledge/spaces/{id}/documents` | 空间可编辑 | multipart；返回 `202 {documentId,versionId,jobId}` |
| `GET /knowledge/documents/{id}` | 空间可读 | 元信息、版本和处理状态，不默认返回原文 |
| `POST /knowledge/documents/{id}/versions/{versionId}/publish` | 空间可编辑 | 仅 READY 版本；原子切换 `published_version_id`，返回 `200` |
| `POST /knowledge/documents/{id}/disable` | 空间可编辑 | 清空发布指针并立即退出检索 |
| `POST /knowledge/documents/{id}/versions/{versionId}/retry` | 空间可编辑 | 仅 FAILED 版本；幂等复用或创建重试 job |
| `DELETE /knowledge/documents/{id}` | 空间可编辑 | 软归档、撤回所有版本并审计；`204` |
| `POST /ai/answers` | 登录且有空间可读权 | `202 {jobId}`；完成后轮询 job |
| `POST /tickets/{id}/ai-drafts` | 可访问工单 | `202 {jobId}` |
| `GET /jobs/{id}` | 请求者或授权操作者 | 状态、结果、错误码和重试建议 |
| `POST /ai/suggestions/{id}/feedback` | 可读建议 | `{feedback:"ACCEPTED"|"REJECTED",reason?}` |

## 主要 DTO

```json
{
  "title": "登录后页面返回 502",
  "description": "今天 10:30 起在测试环境出现……",
  "serviceId": "2a9509e3-7dc3-49d4-a176-075f75aee032",
  "priority": "NORMAL"
}
```

```json
{
  "jobId": "fb7b44f4-9e65-4f10-b43e-9b506bfc970a",
  "status": "COMPLETED",
  "result": {
    "draft": "您好，我们正在核对网关日志……",
    "citations": [{"documentId":"…","title":"测试环境网关排障手册","version":3,"page":4,"chunkId":"…","snippet":"先确认上游健康检查……"}],
    "requiresHumanReview": true
  }
}
```

## Docling 内部服务契约

仅 `api` 到 `docling` 的 Compose 内网可访问；使用 `Authorization: Bearer <DOCLING_INTERNAL_TOKEN>`。请求为 multipart `POST /internal/v1/convert`，字段 `documentVersionId`、`sha256`、`originalFileName`、`sourceFile`。不接受 URL、任意路径或远程引用；Java 读取受控文件后把有限大小文件流转给 Docling。单次超时 90 秒，输入上限仍为 20 MiB/100 PDF 页；调用失败返回稳定 `errorCode`。

```json
{
  "conversionId":"…",
  "documentVersionId":"…",
  "sourceSha256":"…",
  "status":"SUCCEEDED",
  "markdown":"# 标题\n正文……",
  "pages":[{"pageNo":1,"headingPath":["标题"],"text":"正文……"}],
  "warnings":[],
  "parserVersion":"docling-2.140.0"
}
```

实现用 Docling Python SDK `DocumentConverter`，把 `ConversionResult` 转换为本项目 DTO；不把 Python 对象序列化到 API。服务只在临时受限目录处理文件，完成/失败后清理临时文件；日志不记正文。

## 校验规则

- 工单标题 1–200 字符，正文 1–20,000 字符；comment 1–10,000 字符。
- 上传白名单 PDF/DOCX/Markdown/TXT；默认最大 20 MiB、PDF 最大 100 页；配置可调但边界必须服务端执行。
- 引用只能来自本次召回集合；引用指向撤回文档或用户无权文档时，结果拒绝展示并记安全事件。
- `POST /ai/answers` 最多 4,000 字符输入；请求超时返回可查询 job，不让浏览器长连接无限等待。
- 登录创建随机不透明 bearer token，后端只存 token hash；30 分钟过期，无 refresh token，页面刷新后重新登录。`POST /auth/logout` 撤销 token；前端 token 仅存内存，不用 Cookie/localStorage。

## 前端页面与状态

| 路由/页面 | 主要组件 | 必需状态 |
|---|---|---|
| `/login` | 登录表单 | 校验失败、账户停用、网络错误 |
| `/tickets` | 筛选、分页、队列切换、列表 | 首屏加载、空结果、403、重试 |
| `/tickets/new` | 新建表单 | 幂等重复提交、字段错误、创建成功跳转 |
| `/tickets/:id` | 工单信息、公开回复/内部备注、状态历史、AI 草稿 | 权限隐藏、并发 409、保存中、AI job 等待/失败/重试 |
| `/knowledge` 与 `/knowledge/spaces/:id` | 空间 ACL、上传、文档版本和发布 | 解析进度、失败码、旧版本仍发布、撤回确认 |
| `/assistant` | 知识问答、引用侧栏 | 无证据拒答、引用来源不可访问、provider 错误 |

前端展示只消费后端权限裁剪结果；无权限时显示 403/空结果，不靠隐藏按钮做安全控制。AI 草稿必须提供明确“采纳/拒绝”按钮和引用；采纳仅把内容复制到编辑框，用户再次提交才产生公开回复。

## Ticknova V2 增量契约（2026-10-21）

本节覆盖旧阶段同名端点；前缀仍为 `/api/v1`。新增业务已实现，整体验收结果见第12篇。

| 方法与路径 | 输入与权限 | 返回/规则 |
|---|---|---|
| GET `/workspace/portal?q=` | 登录；q最多256字符；先过滤当前ACL/归属/队列 | `{query,knowledge,tickets,popularDocuments,categories}`；知识/工单各最多10条，热门最多6条；无浏览量标为最近发布 |
| POST `/knowledge/documents/{id}/views` | 当前可读、READY、APPROVED发布源 | 204；每用户/文档/UTC日最多一次 |
| GET `/workspace/analytics?from=&to=` | ADMIN；YYYY-MM-DD，年份0001–9999，含首尾UTC日，最多366天，默认30天 | `{from,to,ticketTotal,statusCounts,priorityCounts,daily,knowledge,ai}` |
| GET/POST `/knowledge/spaces/{id}/categories` | 读需canRead；写需显式ADMIN或KB_EDITOR且空间canEdit；创建`{name,parentId?}` | Category数组 / 201 Category；最多5层，禁止循环/跨空间 |
| PATCH/DELETE `/knowledge/categories/{id}` | 同上内容写权；PATCH`{name,parentId,version}`，DELETE`{version}` | Category / 204；版本冲突或非空删除409 |
| POST `/knowledge/spaces/{id}/articles` | 同上；Idempotency-Key；`{title,categoryId,content}` | 201 Draft |
| GET/PUT `/knowledge/documents/{id}/draft` | 同上；PUT`{title,categoryId,content,version}` | Draft；CAS冲突409；无已存草稿时优先用最新解析就绪文件版本初始化正文，GET不写库，首次PUT要求version0；已存草稿保持原样 |
| GET `/knowledge/documents/{id}/versions/{versionId}/draft-source` | 当前空间读+编辑及内容写角色；仅READY且有片段的版本 | `{versionId,versionNo,importable,reason,content}`；文件解析来源合并重叠片段，超编辑器预算时`importable=false`且不截断、不写库；旧空草稿需前端预览后确认并通过原PUT版本锁保存 |
| POST `/knowledge/documents/{id}/draft/versions` | 同上；Idempotency-Key；`{version}` | 202 `{documentId,versionId,jobId}`；私有Markdown→真实Docling |
| GET `/knowledge/documents/{id}/versions/{versionId}/content` | 当前空间权限/版本可见性；普通读者仅当前已审核发布 | RichDoc或null；文件源使用chunks |
| POST `/knowledge/documents/{id}/versions/{versionId}/review-requests` | 当前可编辑、最新且READY版本 | Detail；进入PENDING |
| GET `/knowledge/review-tasks?page=&size=` | 当前有效显式KB_EDITOR；当前空间同时可读、可编辑，且不是版本作者 | 分页`{items,total,page,size}`，仅当前最新READY/PENDING且有片段的未归档版本；任务含documentId、spaceId、title、versionId、versionNo、fileName、authorId、versionCreatedAt；无权返回403，不将作者自己的版本列为待办。前端入口为“审核待办”(`/knowledge/reviews`)，从任务打开精确版本核对正文 |
| POST `/knowledge/documents/{id}/versions/{versionId}/reviews` | 另一位当前有效KB_EDITOR且空间canEdit；`{decision,reason?}` | Detail；APPROVED/REJECTED，拒绝理由必填且最多500字符 |
| POST `/knowledge/documents/{id}/versions/{versionId}/publish` | 空间内容写权、READY且APPROVED | Detail；原子切换发布指针/标题/分类，新稿期间保留旧发布 |
| POST `/ai/jobs/{id}/cancel` | 原创建者、当前请求范围仍有效 | 204；重复中止幂等，已完成409，他人404，撤销服务器租约 |
| GET `/tickets?unassigned=true` | 原有工单授权范围 | 原分页再筛处理人为空，可组合原筛选 |

Category为`{id,spaceId,parentId,name,version}`；Draft为`{id,spaceId,title,categoryId,content,version,updatedAt,updatedBy}`。知识Summary/Detail增加categoryId；Version增加reviewState、authorId、reviewerId、reviewReason、reviewedAt和服务端计算的canRequestReview/canReview/canPublish；AI Job增加CANCELLED终态。

RichDoc为受限Tiptap JSON：标题1–3、段落、列表、引用、代码、分隔线及基础marks；只允许http/https/mailto链接，不存可执行HTML/图片/嵌入。文章最多100,000个UTF-16字符、1MiB JSON、20,000节点、32层；文件阅读独立上限，不套用创作长度限制。生成Markdown执行配置上传大小限制，哈希对应真实文件字节；不可变rich快照保存阅读格式。

文档列表支持categoryId子树。`/knowledge?spaceId=&categoryId=`验证空间和分类归属；仅成员管理、不可读的空间保留配置入口，不查询分类/正文。精确引用使用documentId/versionId/chunkId；版本/片段不可用时要求重新生成，不替换为其他正文。

分析口径：总量/状态/优先级按区间创建工单；每日解决按当天发生RESOLVED转换的去重工单，可能包含更早创建者；知识为当前快照，AI生成及反馈按区间事件。仅返回计数，不返回业务正文。
