// Only positions and filter URL keys are remembered; ticket data and tokens are never cached here.
const positions = new Map<string, number>()
const destinations = new Map<string, string>()
export function rememberTicketScroll(identity: string, path: string, top: number) { if (positions.size >= 20) positions.delete(positions.keys().next().value || ''); positions.set(JSON.stringify([identity, path]), top); if (destinations.size >= 20) destinations.delete(destinations.keys().next().value || ''); if (path === '/tickets' || path.startsWith('/tickets?')) destinations.set(identity, path) }
export function restoreTicketScroll(identity: string, path: string) { return positions.get(JSON.stringify([identity, path])) ?? null }
export function ticketReturnPath(identity: string) { return destinations.get(identity) || '/tickets' }
