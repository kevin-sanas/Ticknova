import type { User } from './types'
export function hasTicketModule(user: User | null) { return !!user?.roles.some((role) => ['REQUESTER', 'AGENT'].includes(role)) }
export function destinationFor(user: User) { return user.mustChangePassword ? '/change-password' : user.roles.includes('AGENT') ? '/tickets' : user.roles.includes('REQUESTER') ? '/portal' : user.roles.includes('ADMIN') ? '/admin/users' : user.roles.includes('KB_REVIEWER') ? '/knowledge/reviews' : user.roles.includes('KB_EDITOR') ? '/knowledge' : '/no-module' }
export function mayVisit(user: User | null, path: string) {
  if (!user) return false
  if (user.mustChangePassword) return path === '/change-password'
  if (path.startsWith('/admin')) return user.roles.includes('ADMIN')
  if (path === '/knowledge/reviews') return user.roles.includes('KB_REVIEWER')
  if (path === '/tickets/new') return user.roles.some((role) => ['REQUESTER', 'AGENT'].includes(role))
  if (path.startsWith('/tickets')) return hasTicketModule(user)
  return true
}
