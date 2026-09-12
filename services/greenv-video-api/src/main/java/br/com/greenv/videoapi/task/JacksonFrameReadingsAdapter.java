package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.port.FrameReadingsReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One frame's votes, pulled out of {@code assessment.json}.
 *
 * <p>The assessment is organised by cell, not by frame: every measurement carries a
 * {@code frameVotes} array naming the frames that reached the voxel floor in it. Answering
 * "what did this photograph see" therefore means walking every cell and keeping the votes whose
 * {@code frameIndex} matches. A segment's assessment holds hundreds of cells and thousands of
 * votes, which is exactly why this happens here and not in a browser.
 *
 * <p>Two things this deliberately does not do. It never throws: a caller asking about a
 * photograph should still get the photograph when the assessment is missing or malformed. And
 * it never falls back from {@code extent95} to {@code h95} when a cell has no local ground —
 * the plane-relative figure reads higher, and quietly substituting it would inflate a reading
 * that the rest of the system reports as tape-comparable.
 */
@Component
public class JacksonFrameReadingsAdapter implements FrameReadingsReader {

    private final ObjectMapper objectMapper;

    public JacksonFrameReadingsAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public FrameReadings read(byte[] assessment, int canonicalFrame) {
        if (assessment == null || assessment.length == 0) {
            return FrameReadings.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(new String(assessment, StandardCharsets.UTF_8));
            List<FrameReadings.CellVote> votes = new ArrayList<>();
            List<Double> extents = new ArrayList<>();
            long samples = 0;
            int evidenceFor = 0;
            Double largestDisagreement = null;

            for (JsonNode cell : root.path("measurements")) {
                if (namesFrame(cell.path("evidenceFrameIndices"), canonicalFrame)) {
                    evidenceFor++;
                }
                JsonNode vote = voteOf(cell.path("frameVotes"), canonicalFrame);
                if (vote == null) {
                    continue;
                }
                Double localGround = decimal(cell, "localGroundM");
                Double voteH95 = decimal(vote, "h95M");
                Double cellH95 = decimal(cell, "h95M");
                Double voteExtent = above(localGround, voteH95);
                long sampleCount = vote.path("sampleCount").asLong(0);
                samples += sampleCount;
                if (voteExtent != null) {
                    extents.add(voteExtent);
                }
                if (voteH95 != null && cellH95 != null) {
                    double gap = Math.abs(voteH95 - cellH95);
                    largestDisagreement = largestDisagreement == null
                            ? gap
                            : Math.max(largestDisagreement, gap);
                }
                votes.add(new FrameReadings.CellVote(
                        cell.path("coordinate").path("alongRoadM").asDouble(),
                        cell.path("coordinate").path("distanceFromRoadM").asDouble(),
                        voteExtent,
                        above(localGround, cellH95),
                        voteH95,
                        cellH95,
                        sampleCount,
                        cell.path("status").asString("")));
            }

            votes.sort(Comparator.comparingDouble(FrameReadings.CellVote::alongRoadM));
            return new FrameReadings(
                    canonicalFrame,
                    votes.size(),
                    samples,
                    median(extents),
                    extents.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null),
                    largestDisagreement,
                    evidenceFor,
                    votes);
        } catch (RuntimeException unreadable) {
            return FrameReadings.EMPTY;
        }
    }

    private static JsonNode voteOf(JsonNode frameVotes, int canonicalFrame) {
        for (JsonNode vote : frameVotes) {
            if (vote.path("frameIndex").asInt(Integer.MIN_VALUE) == canonicalFrame) {
                return vote;
            }
        }
        return null;
    }

    private static boolean namesFrame(JsonNode indices, int canonicalFrame) {
        for (JsonNode index : indices) {
            if (index.asInt(Integer.MIN_VALUE) == canonicalFrame) {
                return true;
            }
        }
        return false;
    }

    /** Above the cell's own ground. Null in, null out — never the plane-relative figure. */
    private static Double above(Double localGroundM, Double heightM) {
        return localGroundM == null || heightM == null ? null : heightM - localGroundM;
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

    private static Double decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
