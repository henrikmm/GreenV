import { describe, expect, it } from 'vitest';
import { measureHeightSpec } from '../graph/nodes/measurement';
import { resampleMaskNearest } from '../../../geometry';
import { decodeQualityMask } from './grass-quality';
import { addTarget, getMask, setActiveMeasurementObject, setMaskData, ensureMask, paintMask } from './measurement-store';

describe('automatic mask display and correction', () => {
  it('cannot turn an unreviewed semantic field into a single-object height', async () => {
    const context = { inputs: { selection: { value: { semantic: true, points: new Float32Array() } }, plane: { value: {} } }, params: {} };
    const result = await measureHeightSpec.execute(context as unknown as Parameters<typeof measureHeightSpec.execute>[0]);
    expect(result.measurement.value).toBeNull();
    expect(result.measurement.summary).toContain('per-cell grid');
  });
  it('keeps source-sized display masks and exact native measurement bytes', () => {
    const id='quality-display-test';
    addTarget({id,code:'AUTO',name:'Automatic grass',definition:'test',truthM:null,mode:'top_above_floor',suggestedFrame:1,maskInstruction:'Inspect'});
    setActiveMeasurementObject(id);
    const native=decodeQualityMask([0,2],4);
    setMaskData(id,1,4,4,resampleMaskNearest(native,2,2,4,4),{source:'model',temporary:true,nativeSemanticMask:{data:native,width:2,height:2}});
    expect(ensureMask(4,4).data.reduce((a,b)=>a+b,0)).toBe(8);
    expect(getMask(id,1)?.nativeSemanticMask?.data).toEqual(native);
    paintMask(3,3,1,false);
    expect(getMask(id,1)?.nativeSemanticMask).toBeUndefined();
  });
  it('rejects overlapping or out-of-bounds pixel runs',()=>{
    expect(()=>decodeQualityMask([0,4,2,1],5)).toThrow();
    expect(()=>decodeQualityMask([0,6],5)).toThrow();
  });
});
