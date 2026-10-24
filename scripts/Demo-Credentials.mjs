import { readFile, writeFile } from 'node:fs/promises'

export function parseDemoEnv(content) {
  return Object.fromEntries(content.split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => {
    const separator = line.indexOf('=')
    return [line.slice(0, separator), line.slice(separator + 1)]
  }))
}

function updateDemoEnv(content, values) {
  const newline = content.includes('\r\n') ? '\r\n' : '\n'
  const keys = Object.keys(values)
  const lines = content.split(/\r?\n/)
  if (lines.at(-1) === '') lines.pop()
  const kept = lines.filter(line => !keys.some(key => line.startsWith(`${key}=`)))
  for (const [key, value] of Object.entries(values)) if (value !== null) kept.push(`${key}=${value}`)
  return kept.join(newline) + newline
}

export async function signInDemo(request, base, envPath, username, io = { readFile, writeFile }, prefix = 'DEMO_BROWSER') {
  // Browser regression uses an isolated database, so its credentials must never overwrite demo account keys.
  const key = `${prefix}_${username.toUpperCase()}_PASSWORD`
  const pendingKey = `${prefix}_${username.toUpperCase()}_PENDING_PASSWORD`
  const settings = parseDemoEnv(await io.readFile(envPath, 'utf8'))
  const candidates = [...new Set([settings[pendingKey], settings[key], settings.DEMO_INITIAL_PASSWORD].filter(Boolean))]
  for (const password of candidates) {
    const response = await request.post(`${base}/api/v1/auth/login`, { data: { username, password } })
    if (response.status() === 200) {
      if (settings[pendingKey] || (settings[key] && settings[key] !== password)) {
        const content = await io.readFile(envPath, 'utf8')
        await io.writeFile(envPath, updateDemoEnv(content, { [key]: password, [pendingKey]: null }), 'utf8')
      }
      return { login: await response.json(), password }
    }
    if (response.status() !== 401) throw new Error(`${username} demo login returned HTTP ${response.status()}`)
  }
  throw new Error(`${username} demo password does not match the project .env`)
}

export async function changeDemoPassword(request, base, envPath, username, oldPassword, token, nextPassword, io = { readFile, writeFile }, prefix = 'DEMO_BROWSER') {
  const key = `${prefix}_${username.toUpperCase()}_PASSWORD`
  const pendingKey = `${prefix}_${username.toUpperCase()}_PENDING_PASSWORD`
  const before = await io.readFile(envPath, 'utf8')
  if (parseDemoEnv(before)[pendingKey]) throw new Error(`${username} has an unresolved pending demo password`)
  // Persist the candidate before the server change; an interrupted response can be recovered on the next sign-in.
  await io.writeFile(envPath, updateDemoEnv(before, { [pendingKey]: nextPassword }), 'utf8')
  const response = await request.post(`${base}/api/v1/auth/change-password`, {
    headers: { Authorization: `Bearer ${token}` }, data: { oldPassword, newPassword: nextPassword }
  })
  if (response.status() !== 204) throw new Error(`${username} demo password change returned HTTP ${response.status()}`)
  const after = await io.readFile(envPath, 'utf8')
  await io.writeFile(envPath, updateDemoEnv(after, { [key]: nextPassword, [pendingKey]: null }), 'utf8')
  return nextPassword
}
