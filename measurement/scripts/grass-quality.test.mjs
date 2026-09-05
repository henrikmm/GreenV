import { describe, it, expect } from 'vitest';
import { encodeRuns, decodeRuns, roadContext, qualitySummary, compareAssessments } from './grass-quality.mjs';
import { renderGrassReport } from './grass-report.mjs';
import { scoreSemanticMask } from '../geometry/semantic-mask';
describe('quality evidence',()=>{
  it('keeps missing grass in the denominator and cannot promote a plausible result',()=>{
    const q=qualitySummary({measurements:[{status:'measured',h95M:.35,h95SpreadM:.1}],reviewEvidence:{coverageFraction:1,abstainedCellCount:0}},[{status:'no-grass-detected'},{status:'failed'}],roadContext(),{available:true},{source:'camera-track-offset'});
    expect(q.frames).toEqual({total:2,processed:1,empty:1,failed:1});expect(q.intendedAreaCoverage).toBeNull();expect(q.operationalStatus).toBe('not-ready');expect(q.blockers).toContain('road-metadata-missing');
  });
  it('scores a total miss as zero recall and rejects the wrong frame',()=>{
    expect(scoreSemanticMask([0,0],[1,1],21,21)).toMatchObject({recall:0,iou:0,precision:null});
    expect(()=>scoreSemanticMask([1],[1],21,41)).toThrow('frame mismatch');
  });
  it('retains cells lost by automatic segmentation in paired height comparison',()=>{
    const c={coordinate:{alongRoadM:.25,distanceFromRoadM:.25},status:'measured',h95M:.4};
    expect(compareAssessments({measurements:[]},{measurements:[c]})).toEqual([{cell:'0.25,0.25',automaticStatus:'not-observed',humanStatus:'measured',deltaH95M:null,thresholdChanges:[{heightM:.1,changed:null},{heightM:.3,changed:null}]}]);
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
