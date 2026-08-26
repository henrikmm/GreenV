#!/usr/bin/env bash
set -euo pipefail

api_url="${API_URL:-http://video-api:8080}"
work_root="$(mktemp -d)"
trap 'rm -rf "${work_root}"' EXIT

for attempt in $(seq 1 60); do
  if curl --fail --silent "${api_url}/actuator/health" >/dev/null; then
    break
  fi
  if [[ "${attempt}" == "60" ]]; then
    echo "video API did not become healthy" >&2
    exit 1
  fi
  sleep 1
done

session_id="$(curl --fail --silent \
  --header 'content-type: application/json' \
  --data '{"deviceId":"compose-smoke-device","startedAt":"2026-08-23T12:00:00Z"}' \
  "${api_url}/v2/capture-sessions" | jq --raw-output '.sessionId')"

for segment_index in 0 1 2; do
  segment_root="${work_root}/${segment_index}"
  mkdir -p "${segment_root}"
  video="${segment_root}/source.mp4"
  telemetry="${segment_root}/telemetry.json"
  monotonic_start="$((segment_index * 10000000000))"
  captured_second="$((segment_index * 10))"
  captured_at="$(printf '2026-08-23T12:00:%02dZ' "${captured_second}")"

  ffmpeg -nostdin -v error -f lavfi \
    -i "testsrc2=duration=10:size=320x180:rate=10" \
    -c:v mpeg4 -q:v 5 -y "${video}"
  jq --null-input \
    --arg session_id "${session_id}" \
    --arg captured_at "${captured_at}" \
    --argjson segment_index "${segment_index}" \
    --argjson monotonic_start "${monotonic_start}" \
    '{
      schemaVersion: 1,
      sessionId: $session_id,
      segmentIndex: $segment_index,
      capturedAtUtc: $captured_at,
      monotonicStartNanos: $monotonic_start,
      frameClockSource: "segment_anchor",
      locations: [{
        monotonicNanos: $monotonic_start,
        latitude: -23.5505,
        longitude: -46.6333,
        altitudeMeters: 760.0,
        horizontalAccuracyMeters: 4.0,
        verticalAccuracyMeters: 6.0,
        speedMetersPerSecond: 12.5,
        speedAccuracyMetersPerSecond: 0.5,
        courseDegrees: 180.0,
        courseAccuracyDegrees: 2.0,
        distanceFromSessionStartMeters: ($segment_index * 125.0)
      }],
      motions: [{
        monotonicNanos: $monotonic_start,
        quaternionX: 0.0,
        quaternionY: 0.0,
        quaternionZ: 0.0,
        quaternionW: 1.0,
        gravityX: 0.0,
        gravityY: 0.0,
        gravityZ: -9.81,
        userAccelerationX: 0.0,
        userAccelerationY: 0.0,
        userAccelerationZ: 0.0,
        rotationRateX: 0.0,
        rotationRateY: 0.0,
        rotationRateZ: 0.0
      }]
    }' >"${telemetry}"

  video_sha="$(sha256sum "${video}" | cut -d ' ' -f 1)"
  telemetry_sha="$(sha256sum "${telemetry}" | cut -d ' ' -f 1)"
  segment_url="${api_url}/v2/capture-sessions/${session_id}/segments/${segment_index}"
  common_headers=(
    --header "X-Idempotency-Key: compose-smoke:${session_id}:${segment_index}"
    --header "X-Captured-At: ${captured_at}"
    --header 'X-Duration-Millis: 10000'
  )

  curl --fail --silent --request PUT \
    --header 'content-type: video/mp4' \
    --header "X-Content-SHA256: ${video_sha}" \
    "${common_headers[@]}" \
    --data-binary "@${video}" \
    "${segment_url}/video" >/dev/null
  curl --fail --silent --request PUT \
    --header 'content-type: video/mp4' \
    --header "X-Content-SHA256: ${video_sha}" \
    "${common_headers[@]}" \
    --data-binary "@${video}" \
    "${segment_url}/video" >/dev/null
  curl --fail --silent --request PUT \
    --header 'content-type: application/json' \
    --header "X-Content-SHA256: ${telemetry_sha}" \
    "${common_headers[@]}" \
    --data-binary "@${telemetry}" \
    "${segment_url}/telemetry" >/dev/null
  curl --fail --silent --request POST "${segment_url}/complete" >/dev/null
done

curl --fail --silent \
  --header 'content-type: application/json' \
  --data '{"lastSegmentIndex":2,"endedAt":"2026-08-23T12:00:30Z"}' \
  "${api_url}/v2/capture-sessions/${session_id}/complete" >/dev/null

for attempt in $(seq 1 90); do
  ready_count=0
  for segment_index in 0 1 2; do
    state="$(curl --fail --silent \
      "${api_url}/v2/capture-sessions/${session_id}/segments/${segment_index}" \
      | jq --raw-output '.state')"
    [[ "${state}" == "ready" ]] && ready_count="$((ready_count + 1))"
  done
  if [[ "${ready_count}" == "3" ]]; then
    break
  fi
  if [[ "${attempt}" == "90" ]]; then
    echo "segments did not become ready" >&2
    exit 1
  fi
  sleep 1
done

for segment_index in 0 1 2; do
  manifest="$(curl --fail --silent \
    "${api_url}/v2/capture-sessions/${session_id}/segments/${segment_index}/manifest")"
  encoded_count="$(jq '.encodedFrameCount' <<<"${manifest}")"
  metadata_key="$(jq --raw-output '.frameMetadataObjectKey' <<<"${manifest}")"
  [[ "${encoded_count}" == "100" ]]
  [[ "${metadata_key}" == capture-sessions/*/segments/*/frame-metadata-v2.json ]]
done

session_state="$(curl --fail --silent \
  "${api_url}/v2/capture-sessions/${session_id}" | jq --raw-output '.state')"
[[ "${session_state}" == "ready" ]]

echo "capture smoke passed: ${session_id}, 3 ordered segments, 300 encoded frames"
