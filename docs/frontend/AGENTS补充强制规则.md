## Frontend Quality Gate — Ticknova

任何前端页面、组件、样式、布局、路由或交互修改，
必须首先阅读：

- docs/frontend/UI_DESIGN_SYSTEM.md
- docs/frontend/COMPONENT_INVENTORY.md
- docs/frontend/UI_REGRESSION_CHECKLIST.md

如果相关文档不存在，先检查仓库结构并补齐必要规范，
不得凭空假设现有组件和技术栈。

### Design Authority

Ticknova 的前端当前遵循用户最新的 [Calm Precision SaaS 强制规范](Ticknova 前端质量与交互强制规范.md)。Warm Editorial Enterprise Workspace 保留作为既有布局与品牌参考；发生冲突时以新规范为准。

产品正式名称为 Ticknova。

禁止将 AgentOps 的深色运维视觉体系直接复制到 Ticknova。

设计 Token 和业务规范优先于临时生成的 UI 风格建议。

### Before Changes

开始修改前必须：
1. 阅读相关设计和业务文档。
2. 检查当前实现。
3. 识别直接及间接受影响的组件和页面。
4. 确认验收标准。
5. 大范围修改时先输出实施计划。

### During Changes

- 优先修复根本原因。
- 优先复用已有共享组件。
- 禁止无理由替换技术栈。
- 禁止用全局样式覆盖掩盖局部问题。
- 禁止伪造业务数据和 AI 结果。
- 禁止未经授权执行高风险业务操作。

### After Changes

必须根据修改范围执行：
- 类型检查与 Lint
- 相关组件测试
- 关键路径 E2E
- 共享组件回归
- 适用的视觉回归
- 浏览器实际渲染检查

未执行的测试必须明确说明。

不得声称测试通过，除非确实执行了对应测试并确认通过。
关键验收项未通过时，不得将任务标记为完成。
必须在交付报告中列出未执行的测试、失败的测试和未解决的风险。

---

# 37. `UI_REGRESSION_CHECKLIST.md` 回归检查清单

将以下内容保存为：

`docs/frontend/UI_REGRESSION_CHECKLIST.md`

```markdown
# Ticknova 前端回归检查清单

## 1. 基本信息

- 修改任务：
- 修改日期：
- 修改范围：L1 / L2 / L3 / L4
- 修改页面：
- 修改共享组件：
- 验收人员：

## 2. 视觉检查

- [ ] 品牌色符合 Ticknova Design Tokens
- [ ] 页面字体与字号正确
- [ ] 标题、正文和辅助文本层级清晰
- [ ] 页面间距与组件圆角统一
- [ ] 没有异常留白
- [ ] 没有明显错位
- [ ] 没有文字覆盖
- [ ] 没有意外的横向滚动
- [ ] 图标风格统一
- [ ] 状态颜色与语义一致

## 3. 基础组件

- [ ] Button
- [ ] IconButton
- [ ] Input
- [ ] Textarea
- [ ] Select
- [ ] Combobox
- [ ] MultiSelect
- [ ] Checkbox
- [ ] Radio
- [ ] Switch
- [ ] Badge
- [ ] Tooltip
- [ ] Dropdown
- [ ] Popover
- [ ] Dialog
- [ ] Drawer
- [ ] Toast
- [ ] Alert
- [ ] Tabs
- [ ] Breadcrumbs
- [ ] Sidebar

## 4. 数据组件

- [ ] DataTable
- [ ] Pagination
- [ ] Search
- [ ] Filter
- [ ] Bulk Actions
- [ ] DatePicker
- [ ] DateRangePicker
- [ ] Charts
- [ ] Empty State
- [ ] Loading State
- [ ] Error State

## 5. Ticknova 业务组件

- [ ] 工单状态
- [ ] 工单优先级
- [ ] 工单分配
- [ ] 工单处理时间线
- [ ] AI Copilot
- [ ] 知识引用
- [ ] 文件上传
- [ ] 文档解析状态
- [ ] 知识分类树
- [ ] 富文本编辑器
- [ ] 文档版本管理
- [ ] 知识审核
- [ ] 用户与角色
- [ ] 队列管理
- [ ] 服务目录
- [ ] 审计记录

## 6. 状态检查

根据组件实际支持的状态进行检查。

- [ ] Default
- [ ] Hover
- [ ] Focus
- [ ] Active / Selected
- [ ] Disabled
- [ ] Loading
- [ ] Empty
- [ ] Error
- [ ] Success
- [ ] Conflict
- [ ] Permission Denied

不适用的状态应说明原因，不得默认跳过。

## 7. 交互检查

- [ ] 按钮行为正确
- [ ] 表单校验正确
- [ ] 下拉菜单正常打开和关闭
- [ ] 弹窗关闭后没有残留遮罩
- [ ] 焦点恢复正确
- [ ] 键盘导航正常
- [ ] 表格排序正确
- [ ] 筛选结果正确
- [ ] 分页结果正确
- [ ] 批量操作范围正确
- [ ] Loading 结束后状态正确
- [ ] 请求失败后提供恢复方式
- [ ] 重复提交受到控制
- [ ] 未保存内容得到保护

## 8. AI 交互检查

- [ ] AI 生成状态正确
- [ ] 流式输出顺序正确
- [ ] 用户中止后状态正确
- [ ] 重试不会重复执行危险操作
- [ ] 引用来源真实存在
- [ ] 引用内容与实际文档一致
- [ ] 无知识结果时有明确提示
- [ ] AI 建议与业务执行结果明确区分
- [ ] AI 工具调用遵循权限要求
- [ ] AI 请求失败不会显示为成功

## 9. 响应式检查

- [ ] 1920px
- [ ] 1440px
- [ ] 1280px
- [ ] 768px
- [ ] 390px

检查：
- [ ] 内容没有意外溢出
- [ ] 导航没有遮挡内容
- [ ] 弹窗在视口内
- [ ] 操作按钮可访问
- [ ] 表格策略合理
- [ ] 图表仍然可读
- [ ] 移动端主要流程可操作

## 10. 数据与权限

- [ ] 数据来自真实 API 或明确标注的 Mock
- [ ] 缺失数据没有被错误地补零
- [ ] 日期、单位和时区正确
- [ ] 权限控制正确
- [ ] 未授权资源不可访问
- [ ] 错误信息没有泄露敏感数据
- [ ] 审计信息正确
- [ ] AI 引用没有越权泄露

## 11. 自动化验证

- [ ] TypeScript 类型检查
- [ ] ESLint
- [ ] 单元测试
- [ ] 组件测试
- [ ] E2E 测试
- [ ] 视觉回归
- [ ] 浏览器实际检查

未执行的测试必须明确标注。

## 12. 最终结论

- [ ] 通过
- [ ] 有条件通过
- [ ] 未通过

未解决问题：

阻塞原因：

后续修复计划：
