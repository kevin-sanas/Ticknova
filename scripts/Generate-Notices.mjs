import {readFile,writeFile,readdir,mkdir,realpath} from 'node:fs/promises'
import {resolve,dirname,sep} from 'node:path'
import {fileURLToPath} from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..')
const modules=resolve(root,'frontend/node_modules')
const evidenceTag=process.env.TICKNOVA_EVIDENCE_TAG
if(evidenceTag&&!/^[a-z0-9-]+$/.test(evidenceTag))throw new Error('Invalid evidence tag')
const bom=JSON.parse(await readFile(resolve(root,`docs/evidence/${evidenceTag?`${evidenceTag}-`:''}frontend-bom.json`),'utf8'))
const contents=['Ticknova frontend third-party notices\nGenerated from installed production dependencies.\nThis file does not grant a license to Ticknova original source.\n']
const missing=[],declaredOnly=[]
for(const component of bom.components) {
  if(component.properties?.some(p=>p.name==='cdx:npm:package:development'&&p.value==='true'))continue
  const name=[component.group,component.name].filter(Boolean).join('/')
  const candidate=resolve(modules,name)
  if(!candidate.startsWith(modules+sep))throw new Error('Invalid package path')
  let files=[]
  try {
    const actual=await realpath(candidate)
    if(!actual.startsWith(modules+sep))throw new Error('Package path is outside project dependencies')
    files=(await readdir(actual,{withFileTypes:true})).filter(f=>f.isFile()&&/^(licen[cs]e|copying|notice)([.-]|$)/i.test(f.name))
    contents.push(`\n${'='.repeat(72)}\n${name}@${component.version}\nDeclared: ${JSON.stringify(component.licenses)}\n`)
    for(const file of files)contents.push(`\n--- ${file.name} ---\n${await readFile(resolve(actual,file.name),'utf8')}\n`)
    if(!files.length&&name.startsWith('@rolldown/binding-')) {
      contents.push(`\nNative build binding from the rolldown repository; parent package notices:\n${await readFile(resolve(modules,'rolldown/LICENSE'),'utf8')}\n`)
      files=[{name:'rolldown/LICENSE'}]
    }
    if(!files.length&&name==='lodash-unified') {
      const manifest=JSON.parse(await readFile(resolve(actual,'package.json'),'utf8'))
      contents.push(`\nAuthor declared by upstream package: ${manifest.author}.\nLicense declared by upstream package: ${manifest.license}.\nThe upstream npm artifact does not contain a standalone LICENSE file; no copyright text has been invented.\n`)
      declaredOnly.push({name,version:component.version,author:manifest.author,license:manifest.license,source:'https://www.npmjs.com/package/lodash-unified'})
      files=[{name:'package.json license declaration'}]
    }
  } catch(error) {missing.push(`${name}: ${error.message}`)}
  if(!files.length)missing.push(`${name}: no top-level notice file`)
}
await mkdir(resolve(root,'frontend/public'),{recursive:true})
await writeFile(resolve(root,'frontend/public/THIRD-PARTY-NOTICES.txt'),contents.join(''),'utf8')
await writeFile(resolve(root,`docs/evidence/${evidenceTag?`${evidenceTag}-`:''}notice-generation.json`),JSON.stringify({generatedAt:new Date().toISOString(),missing,declaredOnly},null,2)+'\n')
console.log(`Generated frontend third-party notices; missing=${missing.length}, manifest-only declarations=${declaredOnly.length}`)
if(missing.length)process.exitCode=1
