import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

// Sends only public dependency coordinates/versions to the OSV advisory query API.
const evidence=resolve(dirname(fileURLToPath(import.meta.url)),'../docs/evidence')
const g4=process.argv.includes('--g4')
const evidenceTag=process.env.TICKNOVA_EVIDENCE_TAG
if(evidenceTag&&!/^[a-z0-9-]+$/.test(evidenceTag))throw new Error('Invalid evidence tag')
const backend=JSON.parse(await readFile(resolve(evidence,g4?'g4-backend-bom.json':'backend-bom.json'),'utf8'))
const frontend=JSON.parse(await readFile(resolve(evidence,evidenceTag?`${evidenceTag}-frontend-bom.json`:'frontend-bom.json'),'utf8'))
const includePython=process.argv.includes('--include-python')
const python=includePython?JSON.parse(await readFile(resolve(evidence,'docling-bom.json'),'utf8')):{components:[]}
const packages=[...new Map([...backend.components,...frontend.components,...python.components].filter(c=>c.purl)
  .map(c=>[c.purl,{purl:c.purl,name:[c.group,c.name].filter(Boolean).join('/'),version:c.version}])).values()]
const report={checkedAt:new Date().toISOString(),source:'https://api.osv.dev/v1/querybatch',packageCount:packages.length,status:'NOT_RUN',findings:[]}
try {
  for(let offset=0;offset<packages.length;offset+=100) {
    let pending=packages.slice(offset,offset+100).map(component=>({component,query:{package:{purl:component.purl}}}))
    while(pending.length) {
      const response=await fetch(report.source,{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({queries:pending.map(p=>p.query)}),signal:AbortSignal.timeout(45000)})
      if(!response.ok)throw new Error(`OSV query returned HTTP ${response.status}`)
      const body=await response.json()
      if(!Array.isArray(body.results)||body.results.length!==pending.length)throw new Error('Incomplete advisory response')
      const next=[]
      body.results.forEach((result,index)=>{
        if(result.vulns?.length)report.findings.push({...pending[index].component,advisories:result.vulns.map(v=>v.id)})
        if(result.next_page_token)next.push({...pending[index],query:{...pending[index].query,page_token:result.next_page_token}})
      })
      pending=next
    }
  }
  report.status=report.findings.length?'FINDINGS':'PASS'
} catch(error) {report.status='UNAVAILABLE';report.error=error.message;process.exitCode=2}
await writeFile(resolve(evidence,evidenceTag?`${evidenceTag}-${includePython?'':'app-'}dependency-audit.json`:g4?'g4-dependency-audit.json':includePython?'g3-dependency-audit.json':'dependency-audit.json'),JSON.stringify(report,null,2)+'\n')
console.log(`Dependency advisory query: ${report.status}; packages=${report.packageCount}; affected entries=${report.findings.length}`)
if(report.findings.length)process.exitCode=1
