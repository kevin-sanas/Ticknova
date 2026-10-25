export interface WorkspaceKnowledgeHit {id:string;spaceId:string;title:string;snippet:string;categoryId:string|null;viewCount:number;publishedAt:string|null}
export interface WorkspaceTicketHit {id:string;ticketNo:number|string;title:string;status:string;priority:string;updatedAt:string}
export interface PortalCategory {id:string;spaceId:string;parentId:string|null;name:string;spaceName:string;documentCount:number}
export interface WorkspacePortal {query:string;knowledge:WorkspaceKnowledgeHit[];tickets:WorkspaceTicketHit[];popularDocuments:WorkspaceKnowledgeHit[];categories:PortalCategory[]}
export interface WorkspaceAnalytics {from:string;to:string;ticketTotal:number;statusCounts:{key:string;count:number}[];priorityCounts:{key:string;count:number}[];daily:{date:string;created:number;resolved:number}[];knowledge:{publishedDocuments:number;pendingReviews:number};ai:{completed:number;refused:number;accepted:number;rejected:number}}
