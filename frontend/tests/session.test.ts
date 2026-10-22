import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '../src/stores/session'
import { destinationFor, mayVisit } from '../src/access'
import {bindApiSession} from '../src/api'

const user = { id: 'u1', username: 'requester', displayName: '申请人', roles: ['REQUESTER'], mustChangePassword: false }

beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); sessionStorage.clear(); vi.unstubAllGlobals() })

describe('session and routing', () => {
  it('starts unauthenticated on a fresh app instance and never persists the token', () => {
    const session = useSessionStore()
    session.accept({ accessToken: 'secret', expiresIn: 60, user })
    expect(session.authenticated).toBe(true)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    setActivePinia(createPinia())
    expect(useSessionStore().authenticated).toBe(false)
  })
  it('forces first password change before module access and denies management access to a requester', () => {
    expect(destinationFor({ ...user, mustChangePassword: true })).toBe('/change-password')
    expect(mayVisit(user, '/admin/users')).toBe(false)
    expect(mayVisit(user, '/tickets')).toBe(true)
    expect(destinationFor({ ...user, roles: ['KB_EDITOR'] })).toBe('/knowledge')
    expect(destinationFor({ ...user, roles: ['ADMIN'] })).toBe('/admin/users')
    expect(mayVisit({ ...user, roles: ['ADMIN'] }, '/tickets')).toBe(false)
    expect(mayVisit({ ...user, roles: ['ADMIN'] }, '/knowledge/reviews')).toBe(false)
    expect(mayVisit({ ...user, roles: ['KB_EDITOR'] }, '/knowledge/reviews')).toBe(true)
  })
  it('clears user and bearer when logged out or expired', () => {
    const session = useSessionStore()
    session.accept({ accessToken: 'secret', expiresIn: -1, user })
    expect(session.authenticated).toBe(false)
    session.accept({ accessToken: 'secret', expiresIn: 60, user })
    session.clear('会话已过期')
    expect(session.token).toBe('')
    expect(session.user).toBeNull()
  })
  it('does not clear a replacement session when an older logout settles late', async () => {
    const session=useSessionStore();session.accept({accessToken:'old',expiresIn:60,user})
    bindApiSession({getToken:()=>session.token,onUnauthorized:()=>session.clear()})
    let release!:(value:Response)=>void;vi.stubGlobal('fetch',()=>new Promise<Response>(resolve=>{release=resolve}))
    const logout=session.logout();session.accept({accessToken:'replacement',expiresIn:60,user:{...user,id:'u2'}})
    release(new Response(null,{status:204}));await logout
    expect(session.token).toBe('replacement');expect(session.user?.id).toBe('u2')
  })
})
