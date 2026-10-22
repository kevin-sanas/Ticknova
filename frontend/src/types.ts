export interface User { id: string; username: string; displayName: string; roles: string[]; mustChangePassword: boolean; active?: boolean }
export interface KnowledgeSpace { id: string; name: string; active: boolean; canRead: boolean; canEdit: boolean; canManageMembers: boolean }
export interface KnowledgeMember { userId: string; username: string; displayName: string; canRead: boolean; canEdit: boolean }
export interface KnowledgeCandidate { id: string; username: string; displayName: string }
export type IngestState = 'QUEUED' | 'EXTRACTING' | 'CHUNKING' | 'READY' | 'FAILED'
export interface KnowledgeDocument {
  categoryId?: string | null;
  id: string; spaceId: string; title: string; publicationState: string; latestVersionId: string | null;
  publishedVersionId: string | null; latestIngestState: IngestState | null; versionCount: number; createdAt: string; canEdit: boolean
}
export interface KnowledgeVersion {
  reviewState: 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'; authorId: string; reviewerId: string | null; reviewReason: string | null; reviewedAt: string | null; canRequestReview: boolean; canReview: boolean; canPublish: boolean; hasEligibleReviewer: boolean;
  id: string; versionNo: number; fileName: string; mimeType: string; sha256: string; ingestState: IngestState;
  publicationState: string; errorCode: string | null; createdAt: string; jobId: string | null;
  jobStatus: 'QUEUED' | 'RUNNING' | 'RETRYING' | 'COMPLETED' | 'FAILED' | null; attemptCount: number; chunkCount: number
}
export interface KnowledgeDetail extends KnowledgeDocument { versions: KnowledgeVersion[] }
export interface KnowledgeUpload { documentId: string; versionId: string; jobId: string }
export interface KnowledgeChunk { id: string; chunkIndex: number; pageNo: number | null; headingPath: string | null; content: string }
export type AiKind = 'ANSWER' | 'REPLY' | 'SUMMARY' | 'CLASSIFICATION'
export type AiFeedback = 'ACCEPTED' | 'REJECTED'
export interface AiConfig { mode: string; profile: string; externalProvider: boolean }
export interface AiCitation { documentId: string; versionId: string; version: number; title: string; page: number | null; chunkId: string; snippet: string }
export interface AiResult {
  suggestionId: string; kind: AiKind; mode: string; profile: string; outcome: 'ANSWERED' | 'INSUFFICIENT_EVIDENCE';
  answer: string | null; summary: string | null; serviceId: string | null; priority: string | null; tags: string[];
  citations: AiCitation[]; requiresHumanReview: true; feedback: AiFeedback | null;
}
export interface KnowledgeCategory { id: string; spaceId: string; parentId: string | null; name: string; version: number }
export interface KnowledgeDraft { id: string; spaceId: string; title: string; categoryId: string | null; content: import('./knowledge/rich').RichDoc; version: number; updatedAt: string; updatedBy: string }
export interface KnowledgeDraftSource { versionId: string; versionNo: number; importable: boolean; reason: string | null; content: import('./knowledge/rich').RichDoc | null }
export interface KnowledgeReviewTask { documentId: string; spaceId: string; title: string; versionId: string; versionNo: number; fileName: string; authorId: string; versionCreatedAt: string }
export interface AiJob { id: string; status: 'QUEUED' | 'RUNNING' | 'RETRYING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'; errorCode: string | null; attemptCount: number; result: AiResult | null }
export interface LoginResult { accessToken: string; expiresIn: number; user: User }
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Queue { id: string; code: string; name: string; active: boolean; canProcess: boolean }
export interface Service { id: string; code: string; name: string; active: boolean }
export interface QueueMember { userId: string; username: string; displayName: string; active: boolean; canProcess: boolean }
export interface TicketSummary {
  id: string; ticketNo: string | number; title: string; priority: string; status: string;
  requesterId: string; requesterName: string; queueId: string; queueName: string;
  assigneeId: string | null; assigneeName: string | null; serviceId: string | null; serviceName: string | null;
  version: number; createdAt: string; updatedAt: string; tags: string[];
}
export interface Comment { id: string; ticketId: string; authorId: string; authorName: string; visibility: string; body: string; createdAt: string }
export interface Transition { id: string; fromStatus: string; toStatus: string; reason: string; actorName: string; createdAt: string }
export interface TicketDetail {
  ticket: TicketSummary; description: string; resolutionNote: string | null;
  publicReplies: Comment[]; internalNotes: Comment[]; transitions: Transition[];
  capabilities: { canReadInternal: boolean; canCommentPublic: boolean; canCommentInternal: boolean; canAssign: boolean; canEdit: boolean };
  allowedTransitions: string[];
}
export interface AuditEvent { id: string; actorId: string; actorName?: string; action: string; subjectType: string; subjectId: string; traceId: string; metadata: unknown; createdAt: string }
export const statuses: Record<string, string> = { NEW: '新建', TRIAGED: '已分诊', IN_PROGRESS: '处理中', WAITING_CUSTOMER: '等待申请人', WAITING_THIRD_PARTY: '等待第三方', RESOLVED: '已解决', CLOSED: '已关闭' }
export const priorities: Record<string, string> = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' }
export const roles: Record<string, string> = { REQUESTER: '申请人', AGENT: '工程师', KB_EDITOR: '知识编辑', ADMIN: '管理员' }
export function formatTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date) }
export function fullTime(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }) }
