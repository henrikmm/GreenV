// Bounded local CPU jobs. Artifacts live in OS scratch storage and expire unless downloaded.
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
const script=fileURLToPath(new URL('../../scripts/assess-grass.mjs',import.meta.url));
const jobs=new Map();
let starting = false;
const TTL=60*60*1000;
export async function startGrassQuality(request) {
  if(starting) throw new Error('A local assessment is starting.');
  if(jobs.size>=4) throw new Error('Download or discard an existing quality report first (limit 4).');
  if([...jobs.values()].some(j=>j.status==='running')) throw new Error('A local grass assessment is already running.');
  if(typeof request.runId!=='string'||request.runId.length>200)throw new Error('runId is required');
  starting=true;
  let directory;
  try { directory=await mkdtemp(join(tmpdir(),'verge-grass-')); } catch(error) { starting=false; throw error; }
  const id=randomUUID();
  const child=spawn(process.execPath,[script,'--stdin','--out',directory],{stdio:['pipe','pipe','pipe']});
  const job={id,directory,child,status:'running',progress:null,error:null,startedAt:Date.now()};jobs.set(id,job);starting=false;
  let pending='';
  child.stdout.resume();
  child.stderr.on('data',chunk=>{pending+=chunk;let end;while((end=pending.indexOf('\n'))>=0){const line=pending.slice(0,end);pending=pending.slice(end+1);try{const p=JSON.parse(line);if(p.error)job.error=p.error;else job.progress=p;}catch{ /* Runtime notices are not progress. */ }}if(pending.length>10000)pending=pending.slice(-10000);});
  child.on('error',error=>{job.error=error.message;job.status='failed';});
  child.on('close',code=>{if(job.status==='cancelled')return;job.status=code===0?'done':'failed';if(code!==0)job.error??=`local process exited ${code}`;job.child=null;});
  child.stdin.on('error',()=>{});child.stdin.end(JSON.stringify(request));
  job.timer=setTimeout(()=>void discardGrassQuality(id),TTL);job.timer.unref();
  return grassQualityStatus(id);
}
export function grassQualityStatus(id){const j=jobs.get(id);return j?{id:j.id,status:j.status,progress:j.progress,error:j.error,startedAt:j.startedAt,expiresAt:j.startedAt+TTL}:null;}
export async function grassQualityArtifact(id,name){const j=jobs.get(id);if(!j||j.status!=='done')throw new Error('quality report not ready or expired');if(!['assessment.json','report.html','SHA256SUMS'].includes(name))throw new Error('unknown quality artifact');return readFile(join(j.directory,name));}
export async function discardGrassQuality(id){const j=jobs.get(id);if(!j)return;clearTimeout(j.timer);jobs.delete(id);j.status='cancelled';if(j.child){const child=j.child;await new Promise(resolve=>{child.once('close',resolve);child.kill('SIGTERM');});}await rm(j.directory,{recursive:true,force:true});}
export function stopGrassQualityJobs(){for(const id of [...jobs.keys()]) void discardGrassQuality(id);}
