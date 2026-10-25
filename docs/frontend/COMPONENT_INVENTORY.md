# Ticknova 前端组件清单

## 2026-10-25 阶段 D 页面与管理列表补齐

| 实际组件/页面 | 页面与 API | 本轮状态、权限与边界 | 测试位置 |
|---|---|---|---|
| `AssistantView.vue` | `/assistant`；既有空间、AI 配置、答案与反馈 API | 空间改为 Element Plus 可搜索标签多选，最多 10 个；空选择仍代表全部可读空间。加载、禁用和提交继续绑定真实请求，模型模式与真实引用继续由服务端结果展示 | `tests/assistant.test.ts`、`scripts/Browser-Design.mjs` |
| `AuditView.vue` | `/admin/audit`；`GET /audit-events`、`GET /audit-events/filters` | ADMIN；操作类型和操作人来自实际审计数据，日期按设备时区选日后转为 UTC 半开区间；详情使用右侧 Drawer，保留关闭后的焦点恢复。请求失败不显示成空数据 | `tests/dialog-focus.test.ts`、`scripts/Browser-Design.mjs` |
| `UsersView.vue` | `/admin/users`；`GET /users`、`GET /roles` | ADMIN；用户名/显示名、启停状态、角色组合服务端筛选和分页；查询、翻页、重置在请求期间禁止重复触发；原创建、停用与角色配置弹窗行为不变 | `tests/management-filters.test.ts`、`tests/dialog-focus.test.ts`、`scripts/Browser-Design.mjs` |
| `CatalogView.vue` | `/admin/queues`、`/admin/services`；分页管理端点及既有成员/写接口 | ADMIN；编码/名称、启停状态服务端筛选和分页；公开目录数组接口不变。队列成员 Dialog、确认移除、未保存保护及真实成功/失败反馈保留 | `tests/management-filters.test.ts`、`tests/catalog-race.test.ts`、`tests/unsaved-workspace.test.ts`、`scripts/Browser-Design.mjs` |

工单详情仍保持既有三栏/窄屏抽屉结构和“AI 建议 → 人工确认 → 手动执行”边界，本轮没有复制或重写同类组件。审计中的 `AI_REQUESTED` 与 `AI_REQUEST_COMPLETED` 保持两条不可变事件，暂不在服务端合并，以免丢失请求和完成阶段的独立追踪证据。

## 2026-10-24 当前页面拆分与实际新增组件

下表为本轮实现快照，优先于下方历史调用位置；测试列只标对应代码，不表示本轮已运行通过，实际结果见 `docs/12-测试矩阵与阶段门.md`。当前五角色为 ADMIN/AGENT/REQUESTER/KB_EDITOR/KB_REVIEWER，动作由服务端分文档和版本提供，前端不得自行放宽授权。

| 实际组件/页面 | 层级、Props / Events | 页面与 API | 状态、权限、测试与边界 |
|---|---|---|---|
| `DocumentActionBar.vue`，`src/components` | 业务组件；`actions?,pending?,secondary?` / `action` | `KnowledgeDocumentView` 文档头及具体版本；对应草稿/上传/审核/发布/撤回/归档/重试 API | 只显示 allowedActions，危险动作独立样式、pending 绑定请求；文档动作与精确版本动作由调用页分发；`tests/knowledge.test.ts`、`tests/knowledge-routes.test.ts`；不替代服务端重新鉴权 |
| `KnowledgeView.vue`，`src/views` | 页面；无 Props/Events | `/knowledge`；空间/分类/文档列表与初次上传、文章创建 | 加载、空、错误、搜索分页、分类及上传；创建文章/上传须 KB_EDITOR+canRead+canEdit；不再容纳详情与成员弹窗；`tests/knowledge.test.ts`、`tests/catalog-race.test.ts` |
| `KnowledgeDocumentView.vue`，`src/views` | 页面；路由 `documentId`、`tab=overview|versions|parse`、`versionId/chunkId/fromReview` | `/knowledge/documents/:id`；详情/内容/chunks/版本动作 API | 独立路由页签（知识列表、可选审核待办、当前文档及真实关闭返回）、概览/版本历史/解析结果 tabs、精确引用、失败重试、审核退回理由；KB_EDITOR 执行编辑动作，KB_REVIEWER 仅当前精确 READY/PENDING 非本人版本可审核；普通读者仅当前发布内容；撤权清理、请求代次、无可用审核者提示；`tests/knowledge-routes.test.ts`、`tests/knowledge.test.ts` |
| `KnowledgeSpaceAdminView.vue`，`src/views` | 页面；query `spaceId` | `/admin/knowledge-spaces`；创建空间、members/member-candidates、Membership PUT | ADMIN 创建及成员配置，加载/空/失败/成功、未保存保护；展示成员 roles 与读/编辑/审核，互斥及读取前置校验；管理权不隐含正文；`tests/knowledge-routes.test.ts` |
| `KnowledgeReviewQueue.vue`，`src/components` / `KnowledgeReviewView.vue` | 待办组件 `open(task)` / 页面打开精确版本 | `/knowledge/reviews`；`GET /knowledge/review-tasks` | 当前 KB_REVIEWER+空间读/审核且无编辑 ACL，排除作者；加载/空/错误/分页/撤权清理；`tests/knowledge-review.test.ts` |
| `PortalView.vue`，`src/views` | 页面；query `q`，知识/助手搜索模式 | `/portal`；`GET /workspace/portal` | 桌面两列知识卡、窄屏单列，真实 publishedAt/阅读次数/直接分类 documentCount；REQUESTER“我的工单”、AGENT“可访问工单”；加载/空/错误/会话切换；`tests/portal.test.ts`、`tests/workspace-pages.test.ts` |
| `DailyChart.vue`，`src/components/analytics` | 复合组件；`daily` / 无事件；纯配置函数 `daily-chart-options.ts` | `AnalyticsView` 懒加载；数据来自 analytics 响应 | ECharts 6.1.0、vue-echarts 8.0.1、SVG、Token颜色、ARIA、减少动态效果、自动 resize；ADMIN；配套 HTML 每日表格在父页，加载失败也可读取；`tests/analytics.test.ts` |
| `AnalyticsView.vue`，`src/views` | 页面；query `range=7|14|30`，默认30 | `/admin/analytics`；原 `GET /workspace/analytics?from=&to=` | 真实 KPI/状态/优先级、知识快照、AI区间反馈及每日表格；ADMIN，加载/空/错误/会话失效清理；零工单比例“—”、零反馈“暂无反馈”；`tests/analytics.test.ts` |

继续复用 `RichDocument`、`RichTextEditor`、`KnowledgeCategories`、`KnowledgeError` 与 `KnowledgeFilePicker`，没有另建同类阅读、编辑或上传组件。`RichDocument` 的知识阅读调用方现为独立详情页；`KnowledgeFilePicker` 分别在列表初次上传和详情新版本上传使用。旧 documentId query 深链由路由兼容到独立详情，保留精确版本/片段定位。

## 2026-10-22 Galaxy 第一批选型与影响分析

Galaxy 只作为局部状态与层级参考，仍使用项目现有 Vue 3、Element Plus、原生表单及 `tokens.css`。下面的 Button/Input/Toast 等名称在原表中是建议清单，不能误认为已有对应 Vue 组件。共用基础样式变更属 L3，关联登录、员工门户、工单列表/详情/新建、知识协同/编辑、助手、运营分析及管理弹窗；需检查这些主要调用页，不能只看一张默认态截图。

| 实际实现与现状 | Galaxy 来源及取舍 | 本批适配 | 必须保留的行为 |
|---|---|---|---|
| Element Plus `.el-button`、原生 `.icon-button`：主次层级存在但 hover/active/disabled 细节不齐，窄屏长文案需核对 | [0x3ther Buttons](https://github.com/uiverse-io/galaxy/blob/main/Buttons/0x3ther_heavy-dragon-56.html) 的简洁色变、键盘焦点环和禁用指示；舍弃 hover 展开标签及多色图标 | 只用森林绿/中性色 Token 强化主、次、图标按钮状态，不移动按钮位置 | `loading` 仍由真实请求绑定；危险操作、类型、事件、权限 `disabled`、键盘点击与图标可访问名称不变 |
| 原生 `.field input/select/textarea`、`.search-input` 与 checkbox/radio：边框及小字号使字段边界不清 | [Cobp Inputs](https://github.com/uiverse-io/galaxy/blob/main/Inputs/Cobp_lazy-frog-59.html) 的单一输入区域与搜索图标关系；舍弃浮动阴影、动画装饰和固定宽度 | 使用控件边框、文字、焦点和错误 Token，保持表单排版；仅共享状态样式，不移植静态 HTML | `v-model`、原生校验、选择器键盘导航、禁用和表单失败后的输入保留不变 |
| `ErrorNotice`、`KnowledgeError` 与 `.info-notice/.error-notice`：反馈语义已有，持续提示层级弱 | [Yaya12085 Notifications](https://github.com/uiverse-io/galaxy/blob/main/Notifications/Yaya12085_smooth-seahorse-63.html) 的图文分层；舍弃游戏主题、装饰图形与出现动画 | 用状态边线、对比及内部间距提高可扫读性；继续复用现有组件，不模拟 Toast 成功 | `role=alert/status`、真实 API 错误、traceId、重试事件、409 保留输入说明与权限文案不变 |

第一批暂不改文件上传、解析、AI、富文本、Tooltip/Toast 功能实现；它们进入验收后下一批。组件状态应按适用性检查，加载和成功只能来自真实请求状态。

## 1. 使用原则

新增组件前，必须先检查本清单和现有组件目录。

如果已有组件可以通过合理扩展满足需求，应优先复用。

禁止为了单个页面创建功能重复、视觉不一致的组件。

## 2. 组件记录格式

每个组件必须记录：

- 名称
- 组件层级
- 所属目录
- 业务用途
- 使用页面
- Props
- Events
- 支持状态
- 关联 API
- 权限要求
- 测试位置
- 已知问题

## 3. 基础组件

| 组件 | 层级 | 主要用途 |
|---|---|---|
| Button | Primitive | 操作按钮 |
| IconButton | Primitive | 图标操作 |
| Input | Primitive | 单行输入 |
| Textarea | Primitive | 多行输入 |
| Select | Primitive | 单项选择 |
| Checkbox | Primitive | 多项选择 |
| RadioGroup | Primitive | 互斥选择 |
| Switch | Primitive | 开关 |
| Badge | Primitive | 状态标签 |
| Tooltip | Primitive | 辅助提示 |
| Dialog | Primitive | 模态对话框 |
| Drawer | Primitive | 侧边工作区 |
| Toast | Primitive | 短暂反馈 |
| Alert | Primitive | 持续提示 |

## 4. 复合组件

| 组件 | 层级 | 主要用途 |
|---|---|---|
| DataTable | Composite | 数据列表 |
| DateRangePicker | Composite | 日期筛选 |
| FilterBar | Composite | 搜索与筛选 |
| Pagination | Composite | 数据分页 |
| FileUploader | Composite | 文件上传 |
| RichTextEditor | Composite | 知识编辑 |
| DocumentReader | Composite | 知识阅读 |
| CategoryTree | Composite | 分类导航 |
| CommandPalette | Composite | 全局搜索 |
| AIChatPanel | Composite | AI 对话 |

## 5. Ticknova 业务组件

| 组件 | 主要用途 |
|---|---|
| TicketStatusBadge | 工单状态展示 |
| TicketPriorityBadge | 工单优先级 |
| TicketAssignment | 工单负责人分配 |
| TicketTimeline | 工单处理时间线 |
| KnowledgeCitation | 知识引用 |
| KnowledgeVersionHistory | 知识版本历史 |
| KnowledgeReviewPanel | 知识审核 |
| AIResponseActions | AI 回答操作 |
| AuditTimeline | 审计记录 |

以上为建议清单，必须以实际仓库实现为准。

## 6. 组件新增审批条件

新增共享组件前必须说明：

1. 为什么现有组件无法满足需求。
2. 新组件解决什么重复问题。
3. 是否会影响现有组件。
4. 是否存在更简单的复用方式。
5. 需要哪些测试。
6. 哪些页面将使用它。

## 7. 组件变更规则

修改共享组件时：

1. 检查所有调用位置。
2. 检查所有支持状态。
3. 检查关联样式。
4. 检查键盘和焦点行为。
5. 执行组件测试。
6. 执行受影响页面回归。
7. 更新本清单。

组件清单不得与实际代码长期不一致。

## 8. 历史知识流程的实际组件与页面（2026-10-22）

上文第 3–5 节是设计建议名称，**不是**仓库中已实现组件的声明。本次复核的代码来源为 `frontend/src/components` 与 `frontend/src/views`：

| 实际组件/页面 | 层级与目录 | 用途、Props / Events | 页面与 API | 状态、权限、测试、已知问题 |
|---|---|---|---|---|
| `RichTextEditor.vue` | 复合组件，`src/components` | 富文本输入；`modelValue, disabled` / `update:modelValue, error` | `ArticleEditorView`；草稿 GET/PUT 由页面完成 | 可编辑、禁用、粘贴错误；须有草稿编辑权限；`tests/article-editor.test.ts`、`tests/rich-document.test.ts`；大正文受服务端大小上限约束 |
| `RichDocument.vue` | 复合组件，`src/components` | 安全正文阅读；`content` / 无事件 | `KnowledgeView`、`ArticleEditorView`；页面读取版本正文或解析来源 | 纯阅读、长文、空目录；权限由调用页和 API 裁剪；`tests/knowledge.test.ts`、`tests/article-editor.test.ts` |
| `KnowledgeError.vue` | 复合组件，`src/components` | Problem Details、409 和重试；`error,retry` / `retry` | 知识中心、文章编辑器、分类树等；由调用页 API 提供错误 | 403、409、网络失败、重试；无独立权限；`tests/knowledge.test.ts`、`tests/article-editor.test.ts` |
| `KnowledgeCategories.vue` | 业务组件，`src/components` | 空间分类树；`spaceId,canEdit,modelValue,initialCategories` / `update:modelValue,denied` | `KnowledgeView`；分类列表与管理 API | 加载、空、错误、选中、编辑/删除；阅读及空间编辑 ACL；`tests/knowledge.test.ts` |
| `ArticleEditorView.vue` | 页面，`src/views` | 自动保存草稿、解析来源预览与确认导入、生成审核版本 | `/knowledge/documents/{id}/draft`、`/versions/{versionId}/draft-source`、`/draft/versions` | 加载、空草稿、预览、保存、409、权限失败；空间编辑 ACL；`tests/article-editor.test.ts`；源超过编辑器安全预算时只可在知识中心查看解析结果 |
| `KnowledgeView.vue` | 页面，`src/views` | 文档阅读、版本状态、空间审核待办、审核及失败索引重试 | `/knowledge/review-tasks`、文档详情、审核/发布/索引重试 API | 加载、空、错误、待审核、驳回、索引待处理/失败/重试；索引操作由服务端 `canRetryIndex` 限定空间编辑者；`tests/knowledge.test.ts` |

本节为当日调用位置与范围；2026-10-24 已拆分详情/管理并增加 DocumentActionBar、DailyChart，当前调用方以上方新快照为准。下一次扩展建议清单时应先核查实际文件，避免按建议名称重复创建。

## 9. Galaxy 定向升级选型与影响（2026-10-22）

Galaxy 只作为交互结构参考，不导入样式库或复制动画。现有 Element Plus 按钮/弹层、原生表单与 API 调用继续使用。所有颜色、字号、间距取 `tokens.css`。

| 批次与实际组件 | 原问题、Galaxy 来源与采用元素 | Ticknova 适配及必须保留的行为 | 关联页面与检查 |
|---|---|---|---|
| 第一批：全局按钮及 `.icon-button` | 默认/悬停/焦点层级不清；[Buttons 示例](https://github.com/uiverse-io/galaxy/blob/main/Buttons/0x3ther_heavy-dragon-56.html) 的清晰边框和焦点提示 | 克制的绿系反馈；保留 Element Plus 的 loading/disabled、真实提交和按钮名称 | 登录、门户、工单、知识、用户管理；`galaxy-batch1` 浏览器与全套组件测试 |
| 第一批：原生 Input/Select/Checkbox、状态提示 | 边框对比与辅助文字过弱；[Inputs 示例](https://github.com/uiverse-io/galaxy/blob/main/Inputs/Cobp_lazy-frog-59.html) 的输入区与提示关系、[Notifications 示例](https://github.com/uiverse-io/galaxy/blob/main/Notifications/Yaya12085_smooth-seahorse-63.html) 的语义区分 | 保留原生键盘与浏览器校验、错误正文、失败后的值；不复制装饰性阴影/动效 | 所有表单、错误提示与管理弹窗；同上 |
| 第二批：`KnowledgeFilePicker.vue` | 两处原生文件控件在窄屏、长文件名、禁用及错误时无一致结构；参考 [Inputs 示例](https://github.com/uiverse-io/galaxy/blob/main/Inputs/Cobp_lazy-frog-59.html) 的输入与操作组合 | 新共享组件仅用于知识初次上传和新版本上传；真实文件、大小、格式、清除及错误即时呈现；保留 20 MiB、PDF 页数服务端校验、真实 XHR 进度、失败重试时文件和标题、空间编辑 ACL；`id,label,modelValue,disabled,describedBy,issue` / `update:modelValue` | `KnowledgeView` 两处；`tests/knowledge.test.ts`、`tests/galaxy-components.test.ts`、第二批浏览器回归 |
| 第二批：`AiRequestStatus.vue` | 生成状态仅一行文字，排队/处理中/完成/失败/中止不易扫读；参考 [Notifications 示例](https://github.com/uiverse-io/galaxy/blob/main/Notifications/Yaya12085_smooth-seahorse-63.html) 的消息与状态分区及 [loaders 目录](https://github.com/uiverse-io/galaxy/tree/main/loaders) 的小型活动标记 | 只在真实提交/真实 job 活动时转动，无布局跳动，尊重减少动态效果；成功/失败仅根据服务端状态；保留取消、重试、长等待、引用与反馈逻辑；既有 Props/Events 不变 | 知识助手、工单 AI 建议；`tests/assistant.test.ts`、`tests/galaxy-components.test.ts`、第二批浏览器回归 |
| 第三批：`RichTextEditor.vue` 工具栏与链接表单 | 工具混排、链接表单失焦且禁用后仍可操作；参考 [Buttons 示例](https://github.com/uiverse-io/galaxy/blob/main/Buttons/0x3ther_heavy-dragon-56.html) 的紧凑按钮、可见焦点与禁用区分 | 用现有 Token 分组，窄屏 44px 点击高度；链接校验仍由 `safeHref` 决定，打开聚焦输入、Escape/取消返回触发按钮，禁用时关闭表单；保留既有 `modelValue,disabled`、更新/错误事件、编辑历史与草稿自动保存 | 知识文章编辑页；`tests/article-editor.test.ts`、1440/390px 隔离浏览器检查 |
| 第三批：`AppNavigation.vue` 折叠提示 | 窄屏切换时，原已聚焦的弹层停留在隐藏导航外；参考 [Tooltips 示例](https://github.com/uiverse-io/galaxy/blob/main/Tooltips/Cksunandh_afraid-snail-51.html) 的简短补充提示，未复制其仅悬停实现 | 保留 Element Plus 的鼠标和键盘焦点触发、链接可访问名称；限制宽度和换行，窄屏禁用并隐藏遗留弹层，不引入装饰动画 | 所有桌面工作区导航及移动导航抽屉；隔离浏览器折叠→聚焦→窄屏复测 |
| 第三批：工单/门户加载骨架 | 装饰线条未明确排除屏幕阅读器；对照 [loaders 目录](https://github.com/uiverse-io/galaxy/tree/main/loaders) 后保留现有克制动效，不复制复杂动画 | 只给装饰线条 `aria-hidden`；`role=status` 的真实请求文案和加载/空/错误分支不变 | 工单列表、详情、员工服务门户；全套前端测试及隔离浏览器列表/门户加载检查 |

当批没有独立 Toast 组件或 ElMessage 成功提示，未增加无业务来源的组件。图表在当时保留后续处理，现已在2026-10-24增量实现，见上方新快照。第三批未复制 Galaxy 的 HTML/CSS，未更换组件库或业务接口。
