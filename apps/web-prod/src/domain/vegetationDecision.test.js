import test from 'node:test'
import assert from 'node:assert/strict'
import { classifyVegetation, processVegetation, recommendedOrderPriority, formatVegetationHeight } from './vegetationDecision.js'

const point = (height, extra = {}) => ({
  measurementExtent95P95M: height, measurementLevel: 2, measurementCellsMeasured: 20, ...extra,
})

test('height boundaries keep exactly 10 and 30 cm in the attention band', () => {
  for (const [height, level] of [[0, 1], [0.0999, 1], [0.10, 2], [0.30, 2], [0.3001, 3]]) {
    assert.equal(classifyVegetation(point(height)).level, level)
  }
})

test('missing, negative and non-numeric heights require review', () => {
  for (const height of [null, undefined, NaN, Infinity, -0.1, '0.2']) {
    assert.equal(classifyVegetation(point(height)).orderPriority, null)
  }
})

test('a tall reading cannot bypass the evidence gate', () => {
  for (const extra of [{ measurementLevel: null }, { measurementLevel: 0 }, { measurementCellsMeasured: 19 }]) {
    assert.equal(classifyVegetation(point(0.64, extra)).level, 0)
  }
  assert.equal(classifyVegetation(point(0.64)).level, 3)
})

test('each band has an explicit action and priority calculated from the height', () => {
  for (const [height, priority] of [[0.05, 'baixa'], [0.20, 'media'], [0.64, 'alta']]) {
    const decision = classifyVegetation(point(height))
    assert.equal(decision.orderPriority, priority)
    assert.ok(decision.action.length > 10)
  }
})

test('processing preserves the source records and their API fields', () => {
  const original = Object.freeze(point(0.64, { sessionId: 'capture-1', windowIndex: 3 }))
  const [processed] = processVegetation([original])
  assert.equal(processed.windowIndex, 3)
  assert.equal(processed.sessionId, original.sessionId)
  assert.equal(processed.decision.priority, 'Alta')
  assert.equal(original.decision, undefined)
})

test('combined orders suggest the highest priority and require review for any unknown target', () => {
  assert.equal(recommendedOrderPriority([point(0.05), point(0.64)]), 'alta')
  assert.equal(recommendedOrderPriority([point(0.05)]), 'baixa')
  assert.equal(recommendedOrderPriority([point(0.64), point(null)]), '')
  assert.equal(recommendedOrderPriority([]), '')
})

test('display rounding never contradicts the height bands', () => {
  assert.equal(formatVegetationHeight(0.09999), '< 10 cm')
  assert.equal(formatVegetationHeight(0.30001), '> 30 cm')
  assert.equal(formatVegetationHeight(0.10), '10 cm')
  assert.equal(formatVegetationHeight(0.30), '30 cm')
  assert.equal(formatVegetationHeight(0.637), '63,7 cm')
  assert.equal(formatVegetationHeight(null), '—')
})
