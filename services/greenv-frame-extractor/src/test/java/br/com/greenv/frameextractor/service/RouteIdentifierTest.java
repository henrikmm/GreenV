package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.RouteIdentity.BearingSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Working the road and the direction out of the fixes, instead of asking the driver.
 *
 * <p>The three cases that decide whether this is worth having: a drive at road speed, the walk
 * that is actually on record, and a capture nowhere near a known road.
 */
class RouteIdentifierTest {

    private final RouteIdentifier identifier = new RouteIdentifier(new ObjectMapper());

    /** A point on the SP-021 reference line, KM 12 give or take. */
    private static final double ON_ROAD_LAT = -23.5182;
    private static final double ON_ROAD_LON = -46.8163;

    private static LocationSample fix(double lat, double lon, Double speed, Double course, double accuracy) {
        return new LocationSample(0L, lat, lon, null, accuracy, null, speed, null, course, null, null);
    }

    private static br.com.greenv.frameextractor.domain.SegmentMotion motion(
            double displacement, double accuracy) {
        return new br.com.greenv.frameextractor.domain.SegmentMotion(displacement, displacement, accuracy, 10, true);
    }

    @Test
    void readsTheDirectionFromTheCourseTheVehicleReported() {
        List<LocationSample> driving = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // 100 km/h heading a touch east of due north.
            driving.add(fix(ON_ROAD_LAT + i * 0.0004, ON_ROAD_LON, 27.8, 4.0 + i % 3, 6.0));
        }

        var route = identifier.identify(driving, motion(160, 6));

        assertThat(route.sentido()).isEqualTo("norte");
        assertThat(route.source()).isEqualTo(BearingSource.DOPPLER_COURSE);
        assertThat(route.bearingDegrees()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(2.0));
    }

    /**
     * The mean of 350 and 10 degrees is 0, not 180. Averaging the numbers would report a road
     * running north as running south, which is the one error this vocabulary cannot survive.
     */
    @Test
    void averagesCoursesAsDirectionsAndNotAsNumbers() {
        var route = identifier.identify(
                List.of(
                        fix(ON_ROAD_LAT, ON_ROAD_LON, 25.0, 350.0, 5.0),
                        fix(ON_ROAD_LAT, ON_ROAD_LON, 25.0, 10.0, 5.0)),
                motion(160, 5));

        assertThat(route.sentido()).isEqualTo("norte");
        assertThat(route.bearingDegrees()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void fallsBackToTheAzimuthWhenTheresNoCourseButRealMovement() {
        var route = identifier.identify(
                List.of(
                        fix(ON_ROAD_LAT, ON_ROAD_LON, 25.0, -1.0, 6.0),
                        fix(ON_ROAD_LAT, ON_ROAD_LON + 0.0020, 25.0, -1.0, 6.0)),
                motion(200, 6));

        assertThat(route.source()).isEqualTo(BearingSource.DISPLACEMENT);
        assertThat(route.sentido()).isEqualTo("leste");
    }

    /**
     * The walk on record: 2 m/s, every course reported as -1, and between 2 and 21 m of
     * displacement against fixes accurate to 8-18 m. An azimuth from that is the direction of
     * the noise.
     */
    @Test
    void aWalkYieldsNoDirectionAtAll() {
        List<LocationSample> walking = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            walking.add(fix(ON_ROAD_LAT + i * 0.00002, ON_ROAD_LON, 1.35, -1.0, 11.5));
        }

        var route = identifier.identify(walking, motion(2.1, 11.5));

        assertThat(route.sentido()).isNull();
        assertThat(route.source()).isEqualTo(BearingSource.NONE);
        assertThat(route.rodovia())
                .as("it is still on the road; only the direction is unknowable")
                .isEqualTo("SP-021");
    }

    /**
     * Where the four measured segments actually are: latitude -23.6366, longitude -46.6834,
     * 13.15 km from the nearest mowing polygon. Naming a road there would put a reading on a
     * highway nobody drove.
     */
    @Test
    void aCaptureFarFromAnyKnownRoadIsGivenNone() {
        var route = identifier.identify(
                List.of(fix(-23.636554, -46.683485, 1.35, -1.0, 11.5)), motion(2.1, 11.5));

        assertThat(route.rodovia()).isNull();
        assertThat(route.distanceToRoadMeters()).isNull();
        assertThat(route.namesARoad()).isFalse();
    }

    @Test
    void keepsWhatTheCallerDeclaredOnlyWhereNothingCouldBeDerived() {
        var derived = identifier
                .identify(List.of(fix(-23.636554, -46.683485, 1.35, -1.0, 11.5)), motion(2.1, 11.5))
                .orElse("BR-101", "sul");

        assertThat(derived.rodovia()).isEqualTo("BR-101");
        assertThat(derived.sentido()).isEqualTo("sul");

        List<LocationSample> driving = List.of(
                fix(ON_ROAD_LAT, ON_ROAD_LON, 27.8, 180.0, 6.0),
                fix(ON_ROAD_LAT - 0.0015, ON_ROAD_LON, 27.8, 180.0, 6.0));
        var overridden = identifier.identify(driving, motion(160, 6)).orElse("BR-101", "norte");

        assertThat(overridden.rodovia()).as("evidence wins over a declaration").isEqualTo("SP-021");
        assertThat(overridden.sentido()).isEqualTo("sul");
    }

    @Test
    void noFixesMeansNoRoadAndNoDirection() {
        var route = identifier.identify(List.of(), motion(0, 0));

        assertThat(route.rodovia()).isNull();
        assertThat(route.sentido()).isNull();
    }
}
