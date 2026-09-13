package br.com.greenv.videoapi.domain;

import java.util.UUID;

/**
 * One measured stretch, named the way every other table names it.
 *
 * <p>A segment has no id of its own — {@code (sessionId, segmentIndex)} is its key throughout the
 * pipeline, from the object storage prefix to the queue message — so an order points at the pair.
 */
public record SegmentReference(UUID sessionId, int segmentIndex) {}
