package br.com.greenv.frameextractor.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ffmpeg {@code select} expression that names an explicit set of frames.
 *
 * <p>The shape matters more than it looks. ffmpeg's expression parser caps its tree at
 * {@code MAX_DEPTH 100} and builds {@code a+b+c} left-deep, one level per term, so a flat chain of
 * more than 100 terms fails to parse — and reports it as "Cannot allocate memory", which says
 * nothing about the cause. Measured on ffmpeg 8.1.2: 100 terms parse, 101 do not. The frame cap is
 * 112, so the naive form would have failed on any full segment. Parentheses cost no depth, so a
 * balanced tree of 112 terms sits at depth 8 and parses.
 *
 * <p>Selection is by frame index. {@code select}'s {@code n} counts frames arriving at the filter,
 * which is decoder output order — presentation order for a reordering codec — and
 * {@code ffprobe -show_frames} enumerates the same way. Verified on a fixture holding 19 B-frames:
 * the probe returned monotonic timestamps while the packets came back reordered, and selecting an
 * index returned that frame. {@code FrameTimestampProbe} already rejects a backwards timestamp, so
 * the assumption has a tripwire, and the caller asserts the produced count equals the planned one.
 */
public final class SelectExpression {

    private SelectExpression() {}

    public static String forIndices(List<Integer> frameIndices) {
        if (frameIndices == null || frameIndices.isEmpty()) {
            throw new IllegalArgumentException("at least one frame must be selected");
        }
        List<String> terms = new ArrayList<>(frameIndices.size());
        for (Integer index : frameIndices) {
            if (index == null || index < 0) {
                throw new IllegalArgumentException("frame indices must be non-negative");
            }
            terms.add("eq(n\\," + index + ")");
        }
        return "select=" + balance(terms);
    }

    /** Folds pairwise until one term remains, so depth grows with log2 of the count, not the count. */
    private static String balance(List<String> terms) {
        List<String> level = terms;
        while (level.size() > 1) {
            List<String> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                next.add(i + 1 < level.size()
                        ? "(" + level.get(i) + "+" + level.get(i + 1) + ")"
                        : level.get(i));
            }
            level = next;
        }
        return level.getFirst();
    }

    /** Nesting depth of the built expression, so a test can pin it below ffmpeg's ceiling. */
    static int depthOf(String expression) {
        int depth = 0;
        int deepest = 0;
        for (char c : expression.toCharArray()) {
            if (c == '(') {
                deepest = Math.max(deepest, ++depth);
            } else if (c == ')') {
                depth--;
            }
        }
        return deepest;
    }
}
