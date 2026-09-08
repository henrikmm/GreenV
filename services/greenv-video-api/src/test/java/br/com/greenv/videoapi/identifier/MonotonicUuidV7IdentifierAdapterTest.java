package br.com.greenv.videoapi.identifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MonotonicUuidV7IdentifierAdapterTest {

    @Test
    void encodesTheUnixMillisecondTimestampVersionAndVariant() {
        Instant now = Instant.parse("2026-08-26T12:34:56.789Z");
        var generator = new MonotonicUuidV7IdentifierAdapter(
                Clock.fixed(now, ZoneOffset.UTC),
                new Random(7));

        UUID identifier = generator.next();

        assertThat(identifier.version()).isEqualTo(7);
        assertThat(identifier.variant()).isEqualTo(2);
        assertThat(timestamp(identifier)).isEqualTo(now.toEpochMilli());
    }

    @Test
    void remainsUniqueAndStrictlyOrderedWithinOneMillisecond() {
        var generator = new MonotonicUuidV7IdentifierAdapter(
                Clock.fixed(Instant.parse("2026-08-26T12:34:56.789Z"), ZoneOffset.UTC),
                new Random(11));

        List<UUID> identifiers = IntStream.range(0, 10_000)
                .mapToObj(ignored -> generator.next())
                .toList();

        assertThat(new HashSet<>(identifiers)).hasSize(identifiers.size());
        assertThat(identifiers).isSorted();
        assertThat(identifiers.stream().map(UUID::toString).toList()).isSorted();
    }

    @Test
    void preservesOrderingWhenTheSystemClockMovesBackwards() {
        var clock = new SequenceClock(2_000, 1_000);
        var generator = new MonotonicUuidV7IdentifierAdapter(clock, new Random(13));

        UUID first = generator.next();
        UUID afterRollback = generator.next();

        assertThat(afterRollback).isGreaterThan(first);
        assertThat(timestamp(afterRollback)).isEqualTo(timestamp(first));
    }

    private static long timestamp(UUID identifier) {
        return (identifier.getMostSignificantBits() >>> 16) & 0xFFFF_FFFF_FFFFL;
    }

    private static final class SequenceClock extends Clock {

        private final long[] values;
        private int index;

        private SequenceClock(long... values) {
            this.values = values;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
