package br.com.greenv.videoapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * The distance a segment covered, and why it is not the length of its own track.
 *
 * <p>The numbers here are the ones on file. Session 01a09274 drew tracks of 7.7 to 10.7 metres
 * per ten-second segment and its segments start 67 to 103 metres apart — the drawn track is two
 * to four clustered fixes, not the path. Reading it as the path made a service order's area eight
 * times too small, and the order before that used a flat 150 metres and was fifteen times too
 * large in the other direction.
 */
class SegmentTravelTest {

    private static final Offset<Double> METRE = Offset.offset(1.0);
    private static final Instant START = Instant.parse("2026-09-11T18:51:48Z");

    /** Roughly 90 metres apart at this latitude, which is a segment at 32 km/h. */
    private static SegmentTravel.Fix fix(int index, double lonOffset, int secondsIn) {
        return new SegmentTravel.Fix(
                index, -23.5738, -46.6315 - lonOffset, START.plusSeconds(secondsIn), 10_000);
    }

    @Test
    void measuresBetweenSegmentsRatherThanInsideOne() {
        // Three segments, each ten seconds, each about 91.7 m further west.
        Map<Integer, Double> lengths = SegmentTravel.lengths(List.of(
                fix(0, 0.0000, 0), fix(1, 0.0009, 10), fix(2, 0.0018, 20)));

        assertThat(lengths.get(0)).isCloseTo(91.7, METRE);
        assertThat(lengths.get(1)).isCloseTo(91.7, METRE);
        // The last has nothing after it and takes the median of the rest.
        assertThat(lengths.get(2)).isCloseTo(91.7, METRE);
    }

    /**
     * A missing index is a segment that failed, not a pause. The interval comes from the clock,
     * so the speed stays right and only the span it covers grows.
     */
    @Test
    void survivesAGapInTheIndices() {
        Map<Integer, Double> lengths = SegmentTravel.lengths(List.of(
                fix(0, 0.0000, 0), fix(2, 0.0018, 20)));

        // 183.4 m over 20 s is 9.17 m/s, and this segment lasted 10 s.
        assertThat(lengths.get(0)).isCloseTo(91.7, METRE);
    }

    /** A pair implying 150 km/h on a mowing survey is a bad fix, and must not set the median. */
    @Test
    void ignoresAPairTooFastToBeMovement() {
        Map<Integer, Double> lengths = SegmentTravel.lengths(List.of(
                fix(0, 0.0000, 0), fix(1, 0.0009, 10), fix(2, 0.0500, 20), fix(3, 0.0509, 30)));

        // The jump into index 2 is discarded; 1 and 3 fall back to the median of what remains.
        assertThat(lengths.get(0)).isCloseTo(91.7, METRE);
        assertThat(lengths.get(2)).isCloseTo(91.7, METRE);
        assertThat(lengths.values()).allSatisfy(length -> assertThat(length).isLessThan(200.0));
    }

    @Test
    void answersNothingWhenOnlyOneSegmentHasAPosition() {
        assertThat(SegmentTravel.lengths(List.of(fix(0, 0.0, 0)))).isEmpty();
    }

    @Test
    void skipsSegmentsThatRecordedNoFix() {
        Map<Integer, Double> lengths = SegmentTravel.lengths(List.of(
                new SegmentTravel.Fix(0, null, null, START, 10_000),
                fix(1, 0.0009, 10),
                fix(2, 0.0018, 20)));

        assertThat(lengths).doesNotContainKey(0);
        assertThat(lengths.get(1)).isCloseTo(91.7, METRE);
    }
}
