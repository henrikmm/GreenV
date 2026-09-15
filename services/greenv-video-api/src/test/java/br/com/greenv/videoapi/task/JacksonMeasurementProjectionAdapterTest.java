package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.MeasurementProjection;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * What a map is allowed to believe about a measurement.
 *
 * <p>The fixtures are shaped after the packets in storage on 11 September 2026, including the
 * parts that look like mistakes and are not: a course of -1, a segment whose camera never moved,
 * and a cell that abstained.
 */
class JacksonMeasurementProjectionAdapterTest {

    private final JacksonMeasurementProjectionAdapter adapter =
            new JacksonMeasurementProjectionAdapter(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static final String PACKET =
            """
            {"positions":[
              {"canonicalFrame":1,"latitude":-23.6365,"longitude":-46.6834,"locationQuality":"degraded"},
              {"canonicalFrame":2,"latitude":-23.6366,"longitude":-46.6835,"locationQuality":"good"},
              {"canonicalFrame":3,"latitude":null,"longitude":null,"locationQuality":"unavailable"}],
             "measurement":{"quality":{"measuredCells":2,"abstainedCells":1,"observedCellCoverage":0.667}}}
            """;

    private static String assessment(String... extents) {
        StringBuilder cells = new StringBuilder();
        for (String extent : extents) {
            if (!cells.isEmpty()) {
                cells.append(',');
            }
            cells.append(extent);
        }
        return "{\"assessment\":{\"measurements\":[" + cells + "]}}";
    }

    private static String measured(double extent95) {
        return "{\"status\":\"measured\",\"extent95M\":" + extent95 + "}";
    }

    @Test
    void keepsTheHeightMeasuredFromEachCellsOwnGround() {
        // extent95M, not h95M. The two differ by the cell's local ground - between 8 and 27 cm in
        // the segments measured on 11 September - and only extent95 has ever been graded.
        var projection = adapter.project(bytes(PACKET), bytes(assessment(measured(0.42), measured(0.11))));

        assertThat(projection.extent95MaxM()).isEqualTo(0.42);
        assertThat(projection.extent95P95M()).isEqualTo(0.42);
        assertThat(projection.cellsMeasured()).isEqualTo(2);
        assertThat(projection.cellsAbstained()).isEqualTo(1);
        assertThat(projection.coverage()).isEqualTo(0.667);
    }

    /**
     * One cell of 3.78 m among six is a bush or a tree the mask let through, and it was really
     * there on 11 September. Colouring a stretch by the tallest cell sends a crew to mow it.
     */
    @Test
    void takesTheLevelFromThePercentileRatherThanFromTheTallestCell() {
        String withAnOutlier = assessment(
                measured(0.05), measured(0.06), measured(0.04), measured(0.07), measured(0.05), measured(3.78));

        var projection = adapter.project(bytes(PACKET), bytes(withAnOutlier));

        assertThat(projection.extent95MaxM()).isEqualTo(3.78);
        assertThat(projection.extent95P95M()).isEqualTo(3.78);
        assertThat(projection.level()).isEqualTo(3);
    }

    /**
     * The stretch stands for its worst twentieth, and one cell in eleven is more than that.
     *
     * <p>This is the aggregate's whole point and also its risk. The tall cell here is a real
     * reading and the stretch is coloured by it; what must never reach this function again is a
     * guardrail measured as grass, and that is now the measurement's job, not the percentile's
     * (see STRETCH_PERCENTILE, and measurement/docs/evidence/2026-09-13-car-mount.md).
     */
    @Test
    void oneTallCellInElevenColoursTheStretch() {
        String tenLowAndOneTall = assessment(
                measured(0.05), measured(0.06), measured(0.04), measured(0.07), measured(0.05),
                measured(0.08), measured(0.06), measured(0.05), measured(0.07), measured(0.09), measured(0.55));

        var projection = adapter.project(bytes(PACKET), bytes(tenLowAndOneTall));

        assertThat(projection.extent95MaxM()).isEqualTo(0.55);
        assertThat(projection.extent95P95M()).isEqualTo(0.55);
        assertThat(projection.level()).isEqualTo(3);
    }

    @Test
    void abstainedCellsContributeNoHeight() {
        String mixed = "{\"assessment\":{\"measurements\":["
                + measured(0.2)
                + ",{\"status\":\"insufficient-support\",\"reason\":\"too-few-frames\",\"extent95M\":null}]}}";

        var projection = adapter.project(bytes(PACKET), bytes(mixed));

        assertThat(projection.extent95MaxM()).isEqualTo(0.2);
        assertThat(projection.level()).isEqualTo(2);
    }

    @Test
    void drawsTheTrackFromTheFixesThatHadOne() {
        var projection = adapter.project(bytes(PACKET), bytes(assessment(measured(0.2))));

        assertThat(projection.trackGeoJson())
                .as("a position with no fix is a gap, not a point to interpolate")
                .isEqualTo("{\"type\":\"LineString\",\"coordinates\":[[-46.6834,-23.6365],[-46.6835,-23.6366]]}");
        assertThat(projection.trackCenterLat()).isCloseTo(-23.63655, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(projection.trackLocationQuality())
                .as("the worst fix along the track, so nobody reads it as a survey")
                .isEqualTo("unavailable");
    }

    /**
     * Segment 3 of session 01a089bf-7db0 walked zero metres and still produced two measured
     * cells. A LineString needs two vertices, so one repeated coordinate is a place and not a
     * path.
     */
    /**
     * The length that replaced an assumption.
     *
     * <p>The area on a service order was segments times ten metres times a flat hundred and
     * fifty, so every stretch came out 1500 m². Measured against the tracks on file, a
     * ten-second walking segment covers between seven and twelve metres.
     *
     * <p>The fixture steps one ten-thousandth of a degree in each direction at latitude -23.6,
     * which is 11.06 metres south and 10.20 east — 15.06 by Pythagoras, and haversine agrees to
     * the centimetre at this size.
     */
    @Test
    void measuresHowFarTheCameraTravelled() {
        var projection = adapter.project(bytes(PACKET), bytes(assessment(measured(0.2))));

        assertThat(projection.trackLengthM()).isCloseTo(15.06, org.assertj.core.data.Offset.offset(0.05));
    }

    /** No path, no length. Zero would read as a stretch that runs nowhere, which is different. */
    @Test
    void reportsNoLengthWhenThereIsNoPath() {
        String stationary =
                """
                {"positions":[
                  {"canonicalFrame":1,"latitude":-23.6,"longitude":-46.6,"locationQuality":"good"},
                  {"canonicalFrame":2,"latitude":-23.6,"longitude":-46.6,"locationQuality":"good"}],
                 "measurement":{"quality":{"measuredCells":1,"abstainedCells":0}}}
                """;

        assertThat(adapter.project(bytes(stationary), bytes(assessment(measured(0.2)))).trackLengthM())
                .isNull();
    }

    @Test
    void aCameraThatNeverMovedHasAPlaceAndNoPath() {
        String stationary =
                """
                {"positions":[
                  {"canonicalFrame":1,"latitude":-23.6,"longitude":-46.6,"locationQuality":"degraded"},
                  {"canonicalFrame":2,"latitude":-23.6,"longitude":-46.6,"locationQuality":"degraded"}],
                 "measurement":{"quality":{"measuredCells":2,"abstainedCells":0}}}
                """;

        var projection = adapter.project(bytes(stationary), bytes(assessment(measured(0.2))));

        assertThat(projection.trackGeoJson()).isNull();
        assertThat(projection.trackCenterLat()).isEqualTo(-23.6);
    }

    @Test
    void anUnreadablePacketCostsTheSummaryAndNotTheMeasurement() {
        // The segment was measured; only its summary is missing. Throwing here would poison a
        // message that already cost GPU time over a JSON field.
        assertThat(adapter.project(bytes("{not json"), null)).isEqualTo(MeasurementProjection.EMPTY);
        assertThat(adapter.project(null, null)).isEqualTo(MeasurementProjection.EMPTY);
    }

    @Test
    void geometryIsProjectedEvenWhenTheCellGridCannotBeRead() {
        var projection = adapter.project(bytes(PACKET), null);

        assertThat(projection.trackGeoJson()).isNotNull();
        assertThat(projection.cellsMeasured()).isEqualTo(2);
        assertThat(projection.extent95P95M()).isNull();
        assertThat(projection.level()).as("no height read means no level, never level 1").isNull();
    }

    @Test
    void theThresholdsAreTheOnesTheDashboardDraws() {
        assertThat(MeasurementProjection.levelFor(0.099)).isEqualTo(1);
        assertThat(MeasurementProjection.levelFor(0.10)).isEqualTo(2);
        assertThat(MeasurementProjection.levelFor(0.30)).isEqualTo(2);
        assertThat(MeasurementProjection.levelFor(0.301)).isEqualTo(3);
        assertThat(MeasurementProjection.levelFor(null)).isNull();
    }
}
