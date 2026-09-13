import test from "node:test";
import assert from "node:assert/strict";
import { buildFrameContext, canonicalFrameNumber, segmentContext, sampledTrackLength } from "../src/frame-context.mjs";

// The extractor writes frame-%04d.jpg starting at 1, and Verge Studio parses the first integer
// out of that name. Off by one here misattributes every position in the packet.
test("canonical frame number is the integer in the file name", () => {
  assert.equal(canonicalFrameNumber("frame-0001.jpg"), 1);
  assert.equal(canonicalFrameNumber("frame-0112.jpg"), 112);
  assert.throws(() => canonicalFrameNumber("frame.jpg"), /carries no number/);
});

const telemetry = [
  { index: 0, presentationTimeNanos: 0, capturedAtUtc: "2026-09-08T10:00:00.000Z", locationQuality: "good", location: { latitude: -23.5, longitude: -46.7, horizontalAccuracyMeters: 4 } },
  { index: 15, presentationTimeNanos: 500_000_000, capturedAtUtc: "2026-09-08T10:00:00.500Z", locationQuality: "good", location: { latitude: -23.6, longitude: -46.8, horizontalAccuracyMeters: 4 } },
  { index: 30, presentationTimeNanos: 1_000_000_000, capturedAtUtc: "2026-09-08T10:00:01.000Z", locationQuality: "degraded", location: { latitude: -23.7, longitude: -46.9, horizontalAccuracyMeters: 40 } },
];

const sampled = [
  { index: 0, fileName: "frame-0001.jpg", timestampSeconds: 0 },
  { index: 1, fileName: "frame-0002.jpg", timestampSeconds: 0.5 },
  { index: 2, fileName: "frame-0003.jpg", timestampSeconds: 1.0 },
];

test("each sampled frame takes the telemetry nearest its own timestamp", () => {
  const { frameContext, positions } = buildFrameContext(sampled, telemetry);

  assert.deepEqual(Object.keys(frameContext), ["1", "2", "3"]);
  assert.equal(frameContext[1].capturado_em, "2026-09-08T10:00:00.000Z");
  assert.equal(frameContext[2].capturado_em, "2026-09-08T10:00:00.500Z");
  assert.equal(frameContext[3].capturado_em, "2026-09-08T10:00:01.000Z");

  assert.deepEqual(positions.map((p) => p.encodedFrameIndex), [0, 15, 30]);
  assert.deepEqual(positions.map((p) => p.latitude), [-23.5, -23.6, -23.7]);
  assert.equal(positions[2].locationQuality, "degraded");
});

// km is the field the dashboard is keyed on and the one the pipeline cannot derive. Inventing it
// from reconstructed distance is the substitution measurement/docs/GRASS-QUALITY.md forbids.
test("km is never invented, and coordinates stay outside the packet context", () => {
  const { frameContext, positions } = buildFrameContext(sampled, telemetry, { rodovia: "SP-021", sentido: "norte" });

  for (const context of Object.values(frameContext)) {
    assert.equal(context.km, null);
    assert.equal(context.rodovia, "SP-021");
    assert.equal(context.sentido, "norte");
    assert.deepEqual(Object.keys(context).sort(), ["capturado_em", "km", "rodovia", "sentido"]);
  }
  assert.equal(positions[0].latitude, -23.5, "coordinates survive, beside the packet rather than inside it");
});

test("a frame with no usable telemetry yields nulls rather than a guess", () => {
  const { frameContext, positions } = buildFrameContext(sampled, []);
  assert.equal(frameContext[1].capturado_em, null);
  assert.equal(positions[0].latitude, null);
  assert.equal(positions[0].locationQuality, "unavailable");
});

// The announcement the frame extractor publishes now carries the road, when the operator named
// one. When nobody did, the packet must say so: `road-metadata-missing` in the quality summary is
// the correct and visible outcome, and a placeholder here would put a road on the map that was
// never driven.
test("an announcement with no road leaves the packet honestly empty", () => {
  const { frameContext } = buildFrameContext(sampled, telemetry, { sessionId: "s", segmentIndex: 0 });

  for (const context of Object.values(frameContext)) {
    assert.equal(context.rodovia, null);
    assert.equal(context.sentido, null);
    assert.equal(context.km, null);
  }
  assert.equal(segmentContext([], {}).rodovia, null);
  assert.equal(segmentContext([], {}).sentido, null);
});

test("the road on the announcement reaches the segment context as it stands", () => {
  const announcement = { rodovia: "BR-101", sentido: "norte" };
  const context = segmentContext([{ capturedAtUtc: "2026-09-08T10:00:00.000Z" }], announcement);

  assert.deepEqual(context, {
    rodovia: "BR-101",
    sentido: "norte",
    km: null,
    capturado_em: "2026-09-08T10:00:00.000Z",
  });
});

test("the segment timestamp is the first frame that actually carries one", () => {
  const positions = [{ capturedAtUtc: null }, { capturedAtUtc: "2026-09-08T10:00:00.500Z" }];
  assert.equal(segmentContext(positions).capturado_em, "2026-09-08T10:00:00.500Z");
  assert.equal(segmentContext([]).capturado_em, null);
});

test("the sampled track length is the odometer span, and null when the manifest cannot say", () => {
  const frames = (distances) => distances.map((distanceMeters, index) => ({ index, fileName: `frame-${index + 1}.jpg`, distanceMeters }));
  assert.equal(sampledTrackLength({ samplingStrategy: "distance-groups", sampledFrames: frames([0, 9.9, 19.8, 29.76]) }), 29.76);
  assert.equal(sampledTrackLength({ samplingStrategy: "distance-groups", sampledFrames: frames([12, 22.5]) }), 10.5);
  assert.equal(sampledTrackLength({ samplingStrategy: "uniform-fps", sampledFrames: frames([0, 10]) }), null, "a uniform sample carries no odometer");
  assert.equal(sampledTrackLength({ samplingStrategy: "distance-groups", sampledFrames: [{ index: 0 }, { index: 1 }] }), null);
  assert.equal(sampledTrackLength({ samplingStrategy: "distance-groups", sampledFrames: frames([30, 20]) }), null, "an odometer that runs backwards is not a length");
  assert.equal(sampledTrackLength(null), null);
});
