import { describe, it, expect } from 'vitest';
import { encodeRuns, decodeRuns, roadContext, qualitySummary, compareAssessments } from './grass-quality.mjs';
import { renderGrassReport } from './grass-report.mjs';
import { scoreSemanticMask } from '../geometry/semantic-mask';
import { rolesFor, vehicleOptions } from './grass-pipeline.mjs';
describe('quality evidence',()=>{
  it('keeps missing grass in the denominator and cannot promote a plausible result',()=>{
    const q=qualitySummary({measurements:[{status:'measured',h95M:.35,h95SpreadM:.1}],reviewEvidence:{coverageFraction:1,abstainedCellCount:0}},[{status:'no-grass-detected'},{status:'failed'}],roadContext(),{available:true},{source:'camera-track-offset'});
    expect(q.frames).toEqual({total:2,processed:1,empty:1,failed:1});expect(q.intendedAreaCoverage).toBeNull();expect(q.operationalStatus).toBe('not-ready');expect(q.blockers).toContain('road-metadata-missing');
  });
  it('names a degenerate track, a refused scale anchor and a relaxed ground fit as blockers',()=>{
    const base=[[{status:'segmented'}],roadContext(),{available:true},{source:'camera-track-offset',status:'assumed'}];
    expect(qualitySummary(null,...base).blockers).not.toContain('camera-track-too-short');
    expect(qualitySummary(null,base[0],base[1],base[2],{source:'camera-track-offset',status:'degenerate'}).blockers).toContain('camera-track-too-short');
    expect(qualitySummary(null,...base,{requested:true,applied:false}).blockers).toContain('scale-anchor-unusable');
    expect(qualitySummary(null,...base,{requested:true,applied:true}).blockers).not.toContain('scale-anchor-unusable');
    expect(qualitySummary(null,base[0],base[1],{available:true,relaxedFit:{inlierDistance:0.07}},base[3]).blockers).toContain('ground-fit-relaxed');
    expect(qualitySummary({measurements:[],reviewEvidence:{coverageFraction:0,abstainedCellCount:0,canopyCellCount:4}},...base).canopyCells).toBe(4);
  });
  it('scores a total miss as zero recall and rejects the wrong frame',()=>{
    expect(scoreSemanticMask([0,0],[1,1],21,21)).toMatchObject({recall:0,iou:0,precision:null});
    expect(()=>scoreSemanticMask([1],[1],21,41)).toThrow('frame mismatch');
  });
  it('retains cells lost by automatic segmentation in paired height comparison',()=>{
    const c={coordinate:{alongRoadM:.25,distanceFromRoadM:.25},status:'measured',h95M:.4,extent95M:.35};
    expect(compareAssessments({measurements:[]},{measurements:[c]})).toEqual([{cell:'0.25,0.25',automaticStatus:'not-observed',humanStatus:'measured',deltaH95M:null,deltaExtent95M:null,thresholdChanges:[{heightM:.1,changed:null},{heightM:.3,changed:null}]}]);
  });
  it('round trips mask bytes and refuses malformed evidence',()=>{
    const mask=Uint8Array.from([1,1,0,1,0,0,1]);expect(decodeRuns(encodeRuns(mask),mask.length)).toEqual(mask);
    for(const runs of [[0,9],[2,-1],[1,3,2,1],[1]]) expect(()=>decodeRuns(runs,7)).toThrow();
  });
  it('requires valid metadata rather than inventing a road location',()=>{
    expect(roadContext()).toEqual({rodovia:null,sentido:null,km:null,capturado_em:null});expect(()=>roadContext({km:-1})).toThrow();
  });
  it('embeds all evidence without allowing document text to execute',()=>{
    const html=renderGrassReport({runId:'</script><img src=x onerror=alert(1)>',frames:[]},[]);
    const embedded=html.match(/id="evidence" type="application\/json">(.*?)<\/script>/s)[1];
    expect(embedded).not.toContain('</script>');expect(JSON.parse(embedded).bundle.frames).toEqual([]);
    expect(html).not.toContain('src="https://');
  });
});

describe('semantic class policy',()=>{
  const LABELS=['road','vegetation','terrain'];
  it('keeps terrain-only as the default and accepts an explicit widening',()=>{
    expect(rolesFor(['terrain'],LABELS).roles).toEqual(['excluded','excluded','grass']);
    expect(rolesFor('terrain,vegetation',LABELS)).toEqual({wanted:['terrain','vegetation'],roles:['excluded','grass','grass']});
    expect(rolesFor(' vegetation , vegetation ',LABELS).wanted).toEqual(['vegetation']);
  });
  it('refuses a label the model cannot produce, rather than segmenting nothing',()=>{
    expect(()=>rolesFor(['grass'],LABELS)).toThrow('unknown Cityscapes label(s) grass');
    expect(()=>rolesFor([],LABELS)).toThrow('at least one');
    expect(()=>rolesFor(' , ',LABELS)).toThrow('at least one');
  });
});

describe('a second model asked what is not grass', () => {
  it('is off by default, needs its classes, and must be a registered model', () => {
    const off = vehicleOptions({});
    expect([off.structureModel, off.structureClasses]).toEqual([null, []]);
    const on = vehicleOptions({ structureModel: 'ade20k-b4', structureClasses: 'fence, railing,wall,fence' });
    expect([on.structureModel, on.structureClasses]).toEqual(['ade20k-b4', ['fence', 'railing', 'wall']]);
    expect(() => vehicleOptions({ structureModel: 'ade20k-b4' })).toThrow(/structureClasses/);
    expect(() => vehicleOptions({ structureClasses: 'fence' })).toThrow(/structureModel/);
    expect(() => vehicleOptions({ structureModel: 'nope', structureClasses: 'fence' })).toThrow(/unknown model/);
    expect(on.structureFloor).toBe(0.5);
    expect(vehicleOptions({ structureModel: 'clipseg', structureClasses: 'guardrail,guard rail' }).structureClasses).toEqual(['guardrail', 'guard rail']);
    expect(() => vehicleOptions({ structureFloor: 1.5 })).toThrow(/structureFloor/);
    expect(on.structureModelMask).toBe(true);
    expect(vehicleOptions({ structureModel: 'vistas-r50', structureClasses: 'Guard Rail', structureModelMask: false }).structureModelMask).toBe(false);
    expect(() => vehicleOptions({ structureModelMask: 'no' })).toThrow(/structureModelMask/);
    expect(() => vehicleOptions({ gridOptions: { structureFrames: 0.5 } })).toThrow(/structureFrames/);
    expect(() => vehicleOptions({ gridOptions: { pastEnds: 'clamp' } })).toThrow(/pastEnds/);
    expect(vehicleOptions({ gridOptions: { structureFrames: 3, pastEnds: 'drop' } }).gridOptions).toMatchObject({ structureFrames: 3, pastEnds: 'drop' });
  });
});
