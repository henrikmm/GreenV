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

            for (JsonNode cell : root.path("measurements")) {
                Double localGround = decimal(cell, "localGroundM");
                Double cellH95 = decimal(cell, "h95M");

                for (JsonNode index : cell.path("evidenceFrameIndices")) {
                    accumulator(byFrame, index.asInt(Integer.MIN_VALUE)).evidenceFor++;
                }
                for (JsonNode vote : cell.path("frameVotes")) {
                    int frame = vote.path("frameIndex").asInt(Integer.MIN_VALUE);
                    if (frame == Integer.MIN_VALUE) {
                        continue;
                    }
                    Accumulator into = accumulator(byFrame, frame);
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
