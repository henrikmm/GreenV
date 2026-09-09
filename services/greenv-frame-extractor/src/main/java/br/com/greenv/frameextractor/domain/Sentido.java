package br.com.greenv.frameextractor.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The direction of travel a capture was driven in: {@code norte}, {@code sul}, {@code leste} or
 * {@code oeste}.
 *
 * <p>The API owns the vocabulary and validates it at its own edge; this copy exists because a
 * queue message is data from another process. A value this worker does not recognise is rejected
 * with the extractor's own terminal failure code, where an operator reads failures, rather than
 * carried into a manifest and a measurement packet that would then describe a road nobody can find.
 */
public final class Sentido {

    private static final Set<String> VOCABULARY = Set.of("norte", "sul", "leste", "oeste");

    private Sentido() {
    }

    /** True for a known sentido and for none at all; false only for a value that is neither. */
    public static boolean isKnownOrAbsent(String value) {
        return value == null || VOCABULARY.contains(value.toLowerCase(Locale.ROOT));
    }
}
