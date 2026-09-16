package br.com.greenv.videoapi.domain;

/**
 * Where a session was recorded, in words, from the readings it produced.
 *
 * <p>A session has no place of its own. What it has is a row of measured stretches, each geocoded
 * from its own track centre, and a drive of two hundred metres can cross more than one street. So
 * the label is the first reading's — in capture order, which is the order somebody drove it — and
 * {@code distinctLabels} says how many different streets the whole session resolved to, which is
 * what lets a screen add "and one more" rather than pretend the first name covers the drive.
 *
 * <p>Everything is null or zero when no reading of the session resolved a place: the geocoder has
 * not run yet, or the session never measured anything. That is an answer, and a screen says so
 * rather than inventing a street.
 *
 * @param label the street the session's first geocoded reading is on
 * @param detail that same reading's finer description, typically a house number or a landmark
 * @param distinctLabels how many different streets the session's readings resolved to
 */
public record SessionPlace(String label, String detail, long distinctLabels) {

    public static final SessionPlace NOWHERE = new SessionPlace(null, null, 0);
}
