package br.com.greenv.videoapi.domain;

/**
 * How many readings of each level one session holds.
 *
 * <p>A reading — a trecho on screen — is a measured window of a segment, or the whole segment
 * when the extractor cut it into none. These counts are taken over exactly that set, because a
 * session line that disagreed with the readings screen it links to would make a reader distrust
 * both.
 *
 * <p>{@code unratedCount} is everything else: a reading measured with no usable cell, and a
 * segment the pipeline has not reached. Null is not level 1 — an unknown height is not a short
 * one — so it is counted apart rather than folded into the lowest band.
 */
public record SessionReadingCounts(
        long level1Count, long level2Count, long level3Count, long unratedCount) {

    public static final SessionReadingCounts EMPTY = new SessionReadingCounts(0, 0, 0, 0);
}
