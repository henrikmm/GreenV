package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.port.FrameReadingsReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every frame's votes, from one pass over {@code assessment.json}.
 *
 * <p>The assessment is organised by cell: each measurement carries a {@code frameVotes} array
 * naming the frames that reached the voxel floor in it. So the walk is over cells, and the
 * frames are accumulated on the way through. One pass answers for all of them, which is why
 * this runs once when a measurement is recorded rather than once per click.
 *
 * <p>Two things it deliberately does not do. It never throws: a caller asking about a
 * photograph should still get the photograph when the assessment is missing or malformed. And
 * it never falls back from {@code extent95} to {@code h95} when a cell has no local ground —
 * the plane-relative figure reads higher, and quietly substituting it would inflate a reading
 * the rest of the system reports as tape-comparable.
 */
@Component
public class JacksonFrameReadingsAdapter implements FrameReadingsReader {

    private final ObjectMapper objectMapper;

    public JacksonFrameReadingsAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FrameReadings> readAll(byte[] assessment) {
        if (assessment == null || assessment.length == 0) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(new String(assessment, StandardCharsets.UTF_8));
            Map<Integer, Accumulator> byFrame = new HashMap<>();

            for (JsonNode cell : cellsOf(root)) {
                Double localGround = decimal(cell, "localGroundM");
                Double cellH95 = decimal(cell, "h95M");

                for (JsonNode index : cell.path("evidenceFrameIndices")) {
                    int canonical = canonicalOf(index.asInt(Integer.MIN_VALUE));
                    if (canonical != Integer.MIN_VALUE) {
                        accumulator(byFrame, canonical).evidenceFor++;
                    }
                }
                for (JsonNode vote : cell.path("frameVotes")) {
                    int canonical = canonicalOf(vote.path("frameIndex").asInt(Integer.MIN_VALUE));
                    if (canonical == Integer.MIN_VALUE) {
                        continue;
                    }
                    Accumulator into = accumulator(byFrame, canonical);
                    into.cellsVoted++;
                    into.sampleCount += vote.path("sampleCount").asLong(0);

                    Double voteH95 = decimal(vote, "h95M");
                    Double extent = above(localGround, voteH95);
                    if (extent != null) {
                        into.extents.add(extent);
                    }
                    if (voteH95 != null && cellH95 != null) {
                        double gap = Math.abs(voteH95 - cellH95);
                        into.largestDisagreement = into.largestDisagreement == null
                                ? gap
                                : Math.max(into.largestDisagreement, gap);
                    }
                }
            }

            List<FrameReadings> readings = new ArrayList<>();
            for (Map.Entry<Integer, Accumulator> entry : byFrame.entrySet()) {
                readings.add(entry.getValue().toReadings(entry.getKey()));
            }
            readings.sort(Comparator.comparingInt(FrameReadings::canonicalFrame));
            return List.copyOf(readings);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * The assessment counts frames from zero; the JPEGs are named from one.
     *
     * <p>`frame-context.mjs` in the measurement worker states the rule — "the extractor writes
     * `frame-%04d.jpg` starting at 1. So canonical = FrameRecord.index + 1" — and the packets on
     * record agree exactly: a segment publishing `frame-0001.jpg` through `frame-0103.jpg`
     * carries assessment indices 0 through 102. Checked against three segments on 12 September
     * 2026, each matching at the top of its range.
     *
     * <p>Getting this wrong is not visibly wrong, which is why it has a comment and a test.
     * Neighbouring frames photograph nearly the same patch, so an off-by-one shows plausible
     * heights for the wrong picture.
     */
    private static int canonicalOf(int frameIndex) {
        return frameIndex == Integer.MIN_VALUE ? Integer.MIN_VALUE : frameIndex + 1;
    }

    /**
     * The cells, wherever the file puts them.
     *
     * <p>`assessment.json` wraps the assessment in an `assessment` key, which is what
     * {@code JacksonMeasurementProjectionAdapter} has always read and what this missed on its
     * first attempt: reading the root found nothing, wrote no rows, and failed silently because
     * an unreadable assessment is a legitimate outcome here. The unwrapped shape is accepted too
     * so a future packet that drops the wrapper does not repeat the same quiet nothing.
     */
    private static JsonNode cellsOf(JsonNode root) {
        JsonNode wrapped = root.path("assessment").path("measurements");
        return wrapped.isArray() ? wrapped : root.path("measurements");
    }

    private static Accumulator accumulator(Map<Integer, Accumulator> byFrame, int frame) {
        return byFrame.computeIfAbsent(frame, key -> new Accumulator());
    }

    /** Above the cell's own ground. Null in, null out — never the plane-relative figure. */
    private static Double above(Double localGroundM, Double heightM) {
        return localGroundM == null || heightM == null ? null : heightM - localGroundM;
    }

    private static Double decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static final class Accumulator {
        private final List<Double> extents = new ArrayList<>();
        private int cellsVoted;
        private long sampleCount;
        private int evidenceFor;
        private Double largestDisagreement;

        private FrameReadings toReadings(int canonicalFrame) {
            return new FrameReadings(
                    canonicalFrame,
                    cellsVoted,
                    sampleCount,
                    median(extents),
                    extents.stream().mapToDouble(Double::doubleValue).max().isPresent()
                            ? extents.stream().mapToDouble(Double::doubleValue).max().getAsDouble()
                            : null,
                    largestDisagreement,
                    evidenceFor);
        }

        private static Double median(List<Double> values) {
            if (values.isEmpty()) {
                return null;
            }
            List<Double> sorted = new ArrayList<>(values);
            sorted.sort(Double::compareTo);
            int middle = sorted.size() / 2;
            return sorted.size() % 2 == 1
                    ? sorted.get(middle)
                    : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        }
    }
}
