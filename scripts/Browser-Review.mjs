import {randomBytes} from 'node:crypto'

// These helpers only create explicitly named synthetic accounts in the local demo.
// Existing demo identities and passwords are never changed by the review fixtures.
export async function actorRequest(actor, base, method, path, data, key, expected=[200,201,202,204]) {
  const response=await actor.page.request.fetch(`${base}/api/v1${path}`,{method,headers:{Authorization:`Bearer ${actor.token}`,...(key?{'Idempotency-Key':key}:{})},...(data===undefined?{}:{data})})
  if(!expected.includes(response.status()))throw new Error(`${method} ${path}: HTTP ${response.status()}`)
  return response.status()===204?null:response.json()
}
export async function createEditorFixture(admin,base,rememberSecret=()=>{}){
  return createFixture(admin,base,'KB_EDITOR',rememberSecret)
}
export async function createReviewerFixture(admin,base,rememberSecret=()=>{}){
  return createFixture(admin,base,'KB_REVIEWER',rememberSecret)
}
async function createFixture(admin,base,role,rememberSecret){
  const username=`ui_${role==='KB_REVIEWER'?'reviewer':'editor'}_${Date.now()}_${randomBytes(3).toString('hex')}`
  const initial=`Fixture-${randomBytes(20).toString('hex')}!`,password=`Changed-${randomBytes(20).toString('hex')}!`
  rememberSecret(initial);rememberSecret(password)
  const user=await actorRequest(admin,base,'POST','/users',{username,displayName:`${role==='KB_REVIEWER'?'专职审核':'知识编辑'}验收 ${username.slice(-6)}`,password:initial,roles:[role]})
  const response=await admin.page.request.post(`${base}/api/v1/auth/login`,{data:{username,password:initial}})
  if(response.status()!==200)throw new Error(`Fixture first sign-in: HTTP ${response.status()}`)
  const first=await response.json();rememberSecret(first.accessToken)
  if(!first.user.mustChangePassword)throw new Error('Fixture must require a first password change')
  await actorRequest({page:admin.page,token:first.accessToken},base,'POST','/auth/change-password',{oldPassword:initial,newPassword:password})
  return{username,password,user}
}
export async function permitReview(manager,reviewer,base,spaceId){
  const reviewerId=reviewer.user?.id||reviewer.userId
  if(!manager.user?.roles?.includes('ADMIN')||!reviewerId||!reviewer.user?.roles?.includes('KB_REVIEWER'))throw new Error('Review grants require ADMIN and a KB_REVIEWER identity')
  await actorRequest(manager,base,'PUT',`/knowledge/spaces/${spaceId}/members/${reviewerId}`,{canRead:true,canEdit:false,canReview:true,reason:'Ticknova 专职审核合成验收'})
}
export async function approveVersion(author,reviewer,base,documentId,versionId){
  const prefix=`/knowledge/documents/${documentId}/versions/${versionId}`
  await actorRequest(author,base,'POST',`${prefix}/review-requests`)
  await actorRequest(reviewer,base,'POST',`${prefix}/reviews`,{decision:'APPROVED'})
}
