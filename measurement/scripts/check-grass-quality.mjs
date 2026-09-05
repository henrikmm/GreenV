#!/usr/bin/env node
// Verify the delivered packet without a model, a browser or any cloud access.
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { decodeRuns } from './grass-quality.mjs';
const hash=(v)=>createHash('sha256').update(v).digest('hex');
export async function checkGrassPacket(directory) {
  const sums=await readFile(join(directory,'SHA256SUMS'),'utf8');
  const names = sums.trim().split('\n').map(line => line.split(/\s+/)[1]).sort();
  if(JSON.stringify(names)!==JSON.stringify(['assessment.json','report.html'])) throw new Error('both artifact checksums are required');
  for(const line of sums.trim().split('\n')){const [digest,name]=line.split(/\s+/);if(!['assessment.json','report.html'].includes(name))throw new Error('unexpected artifact');if(hash(await readFile(join(directory,name)))!==digest)throw new Error(`checksum mismatch: ${name}`);}
  const json=await readFile(join(directory,'assessment.json'),'utf8'),bundle=JSON.parse(json),html=await readFile(join(directory,'report.html'),'utf8');
  if(bundle.schemaVersion!=='verge.grass-quality/0.1.0')throw new Error('unsupported schema');
  const embedded=JSON.parse(html.match(/id="evidence" type="application\/json">(.*?)<\/script>/s)?.[1]??'null');
  if(JSON.stringify(embedded?.bundle)!==JSON.stringify(bundle))throw new Error('report and JSON disagree');
  if(embedded.images.length!==bundle.frames.length)throw new Error('source preview missing');
  const identities=new Set();
  for(const [i,f] of bundle.frames.entries()){
    if(f.frameIndex!==i||identities.has(f.canonicalFrame))throw new Error('frame identity mismatch');identities.add(f.canonicalFrame);
    if(f.mask){const decoded=decodeRuns(f.mask.runs,f.mask.width*f.mask.height);if(hash(decoded)!==f.mask.sha256)throw new Error(`mask checksum mismatch at frame ${i}`);}
    for(const field of ['rodovia','sentido','km','capturado_em'])if(!(field in f))throw new Error(`missing provenance field: ${field}`);
  }
  for(const cell of bundle.cells){
    for(const p of cell.pixels){const frame=bundle.frames[p.frameIndex];if(!frame)throw new Error('unknown evidence frame');decodeRuns(p.runs,frame.depthWidth*frame.depthHeight);}
    const source=bundle.assessment.measurements.find(c=>c.coordinate.alongRoadM===cell.coordinate.alongRoadM&&c.coordinate.distanceFromRoadM===cell.coordinate.distanceFromRoadM);
    for(const key of ['h50M','h90M','h95M','frameCount','sampleCount','status'])if(source?.[key]!==cell[key])throw new Error(`cell ${cell.key} disagrees with assessment`);
  }
  if(bundle.quality.operationalStatus!=='not-ready'||(bundle.assessment && bundle.assessment.validationStatus!=='unvalidated'))throw new Error('unexpected validation claim');
  return {runId:bundle.runId,frames:bundle.frames.length,cells:bundle.cells.length,checksums:'pass',reportMatchesJson:true,maskDigests:'pass'};
}
if(process.argv[1]?.endsWith('/check-grass-quality.mjs')) {
  try{process.stdout.write(JSON.stringify(await checkGrassPacket(process.argv[2]))+'\n');}catch(error){process.stderr.write(error.message+'\n');process.exitCode=1;}
}
