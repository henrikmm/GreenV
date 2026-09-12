package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.FrameReadings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * What a photograph is allowed to claim it measured.
 *
 * <p>The first fixture is the one that matters: {@code assessment.json} wraps its cells under an
 * {@code assessment} key, and the first version of this adapter read the root instead. It found
 * nothing, wrote no rows, and reported no error, because an unreadable assessment is a
 * legitimate outcome here. Silence is the failure mode this class exists to prevent.
 */
class JacksonFrameReadingsAdapterTest {

    private static final Offset<Double> OFFSET = Offset.offset(1e-9);

    private final JacksonFrameReadingsAdapter adapter =
            new JacksonFrameReadingsAdapter(new ObjectMapper());

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Two cells. Assessment index 6 votes in both, index 8 in one; those are the JPEGs
     * `frame-0007.jpg` and `frame-0009.jpg`. Ground is 0.10 m in the first cell.
     */
    private static final String WRAPPED =
            """
            {
              "assessment": {
                "measurements": [
                  {
                    "coordinate": {"alongRoadM": 1.0, "distanceFromRoadM": 2.0},
                    "localGroundM": 0.10,
                    "h95M": 0.60,
                    "status": "measured",
                    "evidenceFrameIndices": [6],
                    "frameVotes": [
                      {"frameIndex": 6, "sampleCount": 40, "h50M": 0.3, "h90M": 0.5, "h95M": 0.70},
                      {"frameIndex": 8, "sampleCount": 10, "h50M": 0.2, "h90M": 0.4, "h95M": 0.55}
                    ]
                  },
                  {
                    "coordinate": {"alongRoadM": 1.5, "distanceFromRoadM": 2.0},
                    "localGroundM": 0.20,
                    "h95M": 0.90,
                    "status": "measured",
                    "evidenceFrameIndices": [],
                    "frameVotes": [
                      {"frameIndex": 6, "sampleCount": 60, "h50M": 0.4, "h90M": 0.8, "h95M": 0.90}
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    void readsTheCellsThroughTheAssessmentWrapper() {
        List<FrameReadings> readings = adapter.readAll(bytes(WRAPPED));

        assertThat(readings).extracting(FrameReadings::canonicalFrame).containsExactly(7, 9);
    }

    /**
     * The assessment counts from zero and the JPEGs are named from one.
     *
     * <p>Shipped wrong once. Neighbouring frames photograph nearly the same patch, so an
     * off-by-one shows plausible heights for the wrong picture and nothing looks broken.
     */
    @Test
    void shiftsTheAssessmentIndexOntoTheNumberInTheFileName() {
        List<FrameReadings> readings = adapter.readAll(bytes(WRAPPED));

        // The fixture votes with indices 6 and 8, which are frame-0007.jpg and frame-0009.jpg.
        assertThat(readings).extracting(FrameReadings::canonicalFrame).doesNotContain(6, 8);
    }

    @Test
    void reportsHeightAboveEachCellsOwnGroundAndNotAboveThePlane() {
        FrameReadings seven = adapter.readAll(bytes(WRAPPED)).get(0);

        // Frame 7's two votes become 0.70 - 0.10 and 0.90 - 0.20, so 0.60 m and 0.70 m above
        // each cell's own ground. Read from the plane instead they would be 0.70 and 0.90, and
        // the whole reading would run high by the pedestal.
        assertThat(seven.cellsVoted()).isEqualTo(2);
        assertThat(seven.extent95MedianM()).isCloseTo(0.65, OFFSET);
        assertThat(seven.extent95MaxM()).isCloseTo(0.70, OFFSET);
        assertThat(seven.sampleCount()).isEqualTo(100);
    }

    @Test
    void measuresDisagreementAgainstWhatTheCellSettledOn() {
        List<FrameReadings> readings = adapter.readAll(bytes(WRAPPED));

        // Frame 7 said 0.70 where its first cell concluded 0.60, and agreed exactly on the
        // second. Frame 9 said 0.55 against the same 0.60.
        assertThat(readings.get(0).largestDisagreementM()).isCloseTo(0.10, OFFSET);
        assertThat(readings.get(1).largestDisagreementM()).isCloseTo(0.05, OFFSET);
    }

    @Test
    void countsOnlyTheCellsThatNamedTheFrameAsEvidence() {
        List<FrameReadings> readings = adapter.readAll(bytes(WRAPPED));

        assertThat(readings.get(0).evidenceForCells()).isEqualTo(1);
        assertThat(readings.get(1).evidenceForCells()).isZero();
    }

    @Test
    void acceptsAnAssessmentWithNoWrapper() {
        String unwrapped = WRAPPED
                .replace("\"assessment\": {", "")
                .replaceFirst("\\}\\s*\\}\\s*$", "}");

        assertThat(adapter.readAll(bytes(unwrapped)))
                .extracting(FrameReadings::canonicalFrame)
                .contains(7);
    }

    /** A cell with no local ground contributes a vote but no height, never the plane figure. */
    @Test
    void leavesTheHeightOutWhenTheCellNeverFoundItsGround() {
        String noGround =
                """
                {"assessment": {"measurements": [
                  {"coordinate": {"alongRoadM": 1.0, "distanceFromRoadM": 2.0},
                   "localGroundM": null, "h95M": 0.60, "status": "insufficient-support",
                   "evidenceFrameIndices": [], "frameVotes": [
                     {"frameIndex": 2, "sampleCount": 5, "h50M": 0.2, "h90M": 0.4, "h95M": 0.5}]}
                ]}}
                """;

        FrameReadings three = adapter.readAll(bytes(noGround)).get(0);

        assertThat(three.cellsVoted()).isEqualTo(1);
        assertThat(three.extent95MedianM()).isNull();
        assertThat(three.extent95MaxM()).isNull();
    }

    @Test
    void answersEmptyForRubbishRatherThanThrowing() {
        assertThat(adapter.readAll(null)).isEmpty();
        assertThat(adapter.readAll(new byte[0])).isEmpty();
        assertThat(adapter.readAll(bytes("not json at all"))).isEmpty();
        assertThat(adapter.readAll(bytes("{\"assessment\":{\"measurements\":[]}}"))).isEmpty();
    }
}
