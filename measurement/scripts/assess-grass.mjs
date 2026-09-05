#!/usr/bin/env node
// Explicit output directory is consent to retain a packet. No output directory: JSON to stdout.
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve, join } from "node:path";
import { createHash } from "node:crypto";
import { runGrassPipeline } from "./grass-pipeline.mjs";
import { renderGrassReport } from "./grass-report.mjs";
const args = process.argv.slice(2);
const value = (flag) => args.includes(flag) ? args[args.indexOf(flag)+1] : undefined;
try {
  if (!args.length || args.includes('--help')) {
    process.stdout.write('Usage: node scripts/assess-grass.mjs <saved-run-id> [--out directory] [--context context.json] [--offset metres]\nAll saved frames are processed locally. The output is unvalidated. --out saves JSON, HTML and checksums.\n');
  } else {
    let request;
    if (args.includes('--stdin')) { let raw='';for await(const chunk of process.stdin)raw+=chunk;request=JSON.parse(raw); }
    else request={runId:args[0],offsetM:Number(value('--offset')??2),...(value('--context')?JSON.parse(await readFile(value('--context'),'utf8')):{})};
    const {bundle,images}=await runGrassPipeline(request,(progress)=>process.stderr.write(JSON.stringify(progress)+'\n'));
    const out=value('--out');
    if(out){await mkdir(out,{recursive:true});const json=JSON.stringify(bundle,null,2)+'\n',html=renderGrassReport(bundle,images);await writeFile(join(out,'assessment.json'),json);await writeFile(join(out,'report.html'),html);const digest=(s)=>createHash('sha256').update(s).digest('hex');await writeFile(join(out,'SHA256SUMS'),`${digest(json)}  assessment.json\n${digest(html)}  report.html\n`);process.stdout.write(JSON.stringify({output:resolve(out),contentSha256:bundle.contentSha256,quality:bundle.quality,timing:bundle.timing})+'\n');}
    else process.stdout.write(JSON.stringify(bundle)+'\n');
  }
} catch(error){process.stderr.write(JSON.stringify({phase:'failed',error:error.message})+'\n');process.exitCode=1;}
