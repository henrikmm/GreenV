package br.com.greenv.frameextractor.domain;

/**
 * Which road a segment was driven along, and in which direction, worked out from the fixes.
 *
 * <p>This used to be typed by the operator on the capture screen. That was wrong three times
 * over: the fields were optional, so in practice they came back empty — both sessions on record
 * have them null; a session is declared once while a long drive changes road and direction; and
 * the answer is already in the telemetry, which is a better witness than someone's memory at the
 * start of a route.
 *
 * <p>Both halves abstain rather than guess, and the evidence says which source was used, so an
 * absent value can be explained instead of merely noticed.
 *
 * @param rodovia the highway, or null when the track is not near one in the reference
 * @param sentido one of {@code norte}, {@code sul}, {@code leste}, {@code oeste}, or null
 * @param bearingDegrees the course the direction came from, 0 to 360 clockwise from north
 * @param source how the bearing was obtained, for the record and for a reader deciding whether
 *     to trust it
 * @param distanceToRoadMeters how far the track sat from the road it was matched to
 */
public record RouteIdentity(
        String rodovia,
        String sentido,
        Double bearingDegrees,
        BearingSource source,
        Double distanceToRoadMeters) {

    public static final RouteIdentity UNKNOWN = new RouteIdentity(null, null, null, BearingSource.NONE, null);

    public enum BearingSource {
        /**
         * The platform's own course, which comes from Doppler and does not read a position. The
         * better of the two, and unavailable below walking-to-driving speed: all four segments
         * measured on 11 September 2026 reported {@code -1} for every fix.
         */
        DOPPLER_COURSE,
        /**
         * The azimuth from the first fix to the last, used only when the camera moved far enough
         * that the answer is not noise. At 18 m of accuracy over 2 m of walking it would be.
         */
        DISPLACEMENT,
        /** Neither source qualified, and no direction is reported. */
        NONE,
    }

    public boolean namesARoad() {
        return rodovia != null;
    }

    /** Keeps an operator-supplied value where nothing could be derived, and never the reverse. */
    public RouteIdentity orElse(String declaredRodovia, String declaredSentido) {
        return new RouteIdentity(
                rodovia != null ? rodovia : declaredRodovia,
                sentido != null ? sentido : declaredSentido,
                bearingDegrees,
                source,
                distanceToRoadMeters);
    }
}
