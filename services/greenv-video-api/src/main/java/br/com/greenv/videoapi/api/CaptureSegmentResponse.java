package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import java.time.Instant;
import java.util.UUID;

public record CaptureSegmentResponse(
        int schemaVersion,
        UUID sessionId,
        int segmentIndex,
        String state,
        String idempotencyKey,
        Instant capturedAt,
        long durationMillis,
        Long videoBytes,
        String videoSha256,
        Long telemetryBytes,
        String telemetrySha256,
        String manifestUrl,
        Integer frameCount,
        String errorCode,
        String errorMessage,
        // Worker 2's side, in the same shape as the manifest above: the state, when it happened,
        // and a link rather than the packet. A reader that only got `state: "ready"` could not
        // tell a segment nobody measured from one measured an hour ago, and had to probe the
        // measurement route and read a 409 to find out.
        String measurementState,
        Instant measuredAt,
        String measurementRunId,
        // Never omitted when a measurement exists. A packet built on the fixture mock describes
        // another scene entirely, and a field that is absent reads as "no" to every client.
        Boolean measurementIsMock,
        String measurementUrl,
        // The summary a list can sort and colour by, so a map does not have to fetch one packet
        // per segment to draw a screen. Null throughout until a measurement is recorded.
        Integer measurementLevel,
        Double measurementExtent95P95M,
        Double measurementExtent95MaxM,
        Integer measurementCellsMeasured,
        Integer measurementCellsAbstained,
        Double measurementCoverage,
        Double trackCenterLat,
        Double trackCenterLon,
        String trackLocationQuality,
        // Where the stretch is, in words, resolved from its own track centre and cached on
        // the row. Null while the resolver has not been round yet.
        String placeLabel,
        String placeDetail,
        // Approximate, and named so: reverse geocoding answers with the nearest addressable
        // point, which on a verge is the building across the road.
        String placeHouseNumber,
        String placeRoad,
        // The nearest kilometre post. The posts average 1066 m apart, so this names a
        // stretch of road and never a position; the offset says how loose it is.
        Integer placeKm,
        Double placeKmOffsetM,
        String placeSource,
        String framesUrl,
        // Which stretch of the segment this row is. Null means the segment was measured whole:
        // every reading taken before the extractor started cutting a segment into windows, and
        // any segment whose frames make a single one. It is not a default — a row with a window
        // index is a reading about 25 m of road, and a row without one may be about 200.
        Integer windowIndex,
        Double windowStartMeters,
        Double windowEndMeters,
        // How the segment was cut, on every row of it. A segment row carries these so a session
        // list can show that a segment has eight readings without fetching them.
        Integer windowCount,
        Integer measuredWindowCount) {

    public static CaptureSegmentResponse from(CaptureSegmentDocument segment, String baseUrl) {
        String segmentPath = "/v2/capture-sessions/" + segment.sessionId()
                + "/segments/" + segment.segmentIndex();
        String manifestUrl = segment.manifestObjectKey() == null
                ? null
                : baseUrl + segmentPath + "/manifest";
        // The key is what proves the packet exists; the URL is only how a client fetches it. A
        // window's packet is the same route with the window named, so a client never composes a
        // key of its own.
        String window = segment.windowIndex() == null ? "" : "?window=" + segment.windowIndex();
        String measurementUrl = segment.measurementObjectKey() == null
                ? null
                : baseUrl + segmentPath + "/measurement" + window;
        String framesUrl = segment.manifestObjectKey() == null ? null : baseUrl + segmentPath + "/frames";
        var measurement = segment.measurement();
        var place = segment.place();
        return new CaptureSegmentResponse(
                1,
                segment.sessionId(),
                segment.segmentIndex(),
                segment.state(),
                segment.idempotencyKey(),
                segment.capturedAt(),
                segment.durationMillis(),
                segment.videoBytes(),
                segment.videoSha256(),
                segment.telemetryBytes(),
                segment.telemetrySha256(),
                manifestUrl,
                segment.frameCount(),
                segment.errorCode(),
                segment.errorMessage(),
                segment.measurementState(),
                segment.measuredAt(),
                segment.measurementRunId(),
                segment.measurementIsMock(),
                measurementUrl,
                measurement.level(),
                measurement.extent95P95M(),
                measurement.extent95MaxM(),
                measurement.cellsMeasured(),
                measurement.cellsAbstained(),
                measurement.coverage(),
                measurement.trackCenterLat(),
                measurement.trackCenterLon(),
                measurement.trackLocationQuality(),
                place == null ? null : place.label(),
                place == null ? null : place.detail(),
                place == null ? null : place.houseNumber(),
                place == null ? null : place.road(),
                place == null ? null : place.km(),
                place == null ? null : place.kmOffsetMetres(),
                place == null ? null : place.source(),
                framesUrl,
                segment.windowIndex(),
                segment.windowStartMeters(),
                segment.windowEndMeters(),
                segment.windowCount(),
                segment.measuredWindowCount());
    }
}
