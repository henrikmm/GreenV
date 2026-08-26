package br.com.greenv.videoapi.identifier;

import br.com.greenv.videoapi.port.IdentifierGenerator;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** RFC 9562 UUIDv7 generator with a 42-bit monotonic counter. */
@Component
public class MonotonicUuidV7IdentifierAdapter implements IdentifierGenerator {

    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final long COUNTER_MASK = (1L << 42) - 1;
    private static final long INITIAL_COUNTER_MASK = (1L << 41) - 1;
    private static final long COUNTER_LOW_MASK = (1L << 30) - 1;
    private static final long RANDOM_TAIL_MASK = (1L << 32) - 1;
    private static final long VERSION_7 = 0x7000L;
    private static final long RFC_4122_VARIANT = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final RandomGenerator randomGenerator;

    private long lastTimestamp = -1;
    private long counter;

    public MonotonicUuidV7IdentifierAdapter(Clock clock, RandomGenerator randomGenerator) {
        this.clock = clock;
        this.randomGenerator = randomGenerator;
    }

    @Override
    public synchronized UUID next() {
        long timestamp = clock.millis();
        requireValidTimestamp(timestamp);

        if (timestamp > lastTimestamp) {
            lastTimestamp = timestamp;
            counter = initialCounter();
        } else if (counter < COUNTER_MASK) {
            timestamp = lastTimestamp;
            counter++;
        } else {
            if (lastTimestamp == MAX_TIMESTAMP) {
                throw new IllegalStateException("UUIDv7 timestamp and counter are exhausted");
            }
            timestamp = ++lastTimestamp;
            counter = initialCounter();
        }

        long counterHigh = (counter >>> 30) & 0xFFFL;
        long counterLow = counter & COUNTER_LOW_MASK;
        long mostSignificantBits = (timestamp << 16) | VERSION_7 | counterHigh;
        long leastSignificantBits = RFC_4122_VARIANT
                | (counterLow << 32)
                | (randomGenerator.nextLong() & RANDOM_TAIL_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private long initialCounter() {
        return randomGenerator.nextLong() & INITIAL_COUNTER_MASK;
    }

    private static void requireValidTimestamp(long timestamp) {
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) {
            throw new IllegalStateException("clock is outside the UUIDv7 timestamp range");
        }
    }
}
