import {randomBytes} from 'node:crypto'

// These helpers only create explicitly named synthetic accounts in the local demo.
// Existing demo identities and passwords are never changed by the review fixtures.
export async function actorRequest(actor, base, method, path, data, key, expected=[200,201,202,204]) {
  const response=await actor.page.request.fetch(`${base}/api/v1${path}`,{method,headers:{Authorization:`Bearer ${actor.token}`,...(key?{'Idempotency-Key':key}:{})},...(data===undefined?{}:{data})})
  if(!expected.includes(response.status()))throw new Error(`${method} ${path}: HTTP ${response.status()}`)
  return response.status()===204?null:response.json()
}
export async function createEditorFixture(admin,base,rememberSecret=()=>{}){
  const username=`ui_editor_${Date.now()}_${randomBytes(3).toString('hex')}`
  const initial=`Fixture-${randomBytes(20).toString('hex')}!`,password=`Changed-${randomBytes(20).toString('hex')}!`
  rememberSecret(initial);rememberSecret(password)
  const user=await actorRequest(admin,base,'POST','/users',{username,displayName:`独立审核验收 ${username.slice(-6)}`,password:initial,roles:['KB_EDITOR']})
  const response=await admin.page.request.post(`${base}/api/v1/auth/login`,{data:{username,password:initial}})
  if(response.status()!==200)throw new Error(`Fixture first sign-in: HTTP ${response.status()}`)
  const first=await response.json();rememberSecret(first.accessToken)
  if(!first.user.mustChangePassword)throw new Error('Fixture must require a first password change')
  await actorRequest({page:admin.page,token:first.accessToken},base,'POST','/auth/change-password',{oldPassword:initial,newPassword:password})
  return{username,password,user}
}
export async function permitReview(author,reviewer,base,spaceId){
  const authorId=author.user?.id||author.userId,reviewerId=reviewer.user?.id||reviewer.userId
  if(!authorId||!reviewerId||authorId===reviewerId)throw new Error('Review requires distinct fixture identities')
  await actorRequest(author,base,'PUT',`/knowledge/spaces/${spaceId}/members/${reviewerId}`,{canRead:true,canEdit:true,reason:'Ticknova UI V2 独立审核合成验收'})
}
export async function approveVersion(author,reviewer,base,documentId,versionId){
  const prefix=`/knowledge/documents/${documentId}/versions/${versionId}`
  await actorRequest(author,base,'POST',`${prefix}/review-requests`)
  await actorRequest(reviewer,base,'POST',`${prefix}/reviews`,{decision:'APPROVED'})
}
