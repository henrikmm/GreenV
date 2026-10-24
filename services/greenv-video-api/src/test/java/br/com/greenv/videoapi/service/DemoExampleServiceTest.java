package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The index is read from the bucket at most once a minute, and a missing one is not remembered. */
class DemoExampleServiceTest {

    private static final Instant START = Instant.parse("2026-10-24T14:30:00Z");

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private Instant now = START;

    @Test
    void keepsTheIndexForAMinuteButNotItsAbsence() {
        DemoExampleService service = new DemoExampleService(
                storage(), (key, ttl) -> "signed:" + key, JsonMapper.builder().build(), clock(), properties());

        assertThat(service.examples()).isEmpty();
        objects.put("demo/examples/index.json",
                "{\"examples\":[{\"id\":\"01-a\",\"frames\":2}]}".getBytes(StandardCharsets.UTF_8));
        assertThat(service.examples()).as("an index uploaded mid-demo shows at once").hasSize(1);

        objects.put("demo/examples/index.json",
                "{\"examples\":[{\"id\":\"01-a\"},{\"id\":\"02-b\"}]}".getBytes(StandardCharsets.UTF_8));
        now = START.plusSeconds(59);
        assertThat(service.examples()).hasSize(1);
        assertThat(reads.get()).isEqualTo(1);

        now = START.plusSeconds(61);
        assertThat(service.examples()).hasSize(2);
        assertThat(reads.get()).isEqualTo(2);

        assertThat(service.run("01-a").frames()).isEmpty();
        assertThat(service.run("01-a").manifest()).as("no manifest uploaded").isNull();
    }

    private Clock clock() {
        return new Clock() {
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
                return now;
            }
        };
    }

    private CaptureObjectStorage storage() {
        return new CaptureObjectStorage() {
            @Override
            public StoredObject put(String objectKey, InputStream input, String expectedSha256, long maximumBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean exists(String objectKey) {
                return objects.containsKey(objectKey);
            }

            @Override
            public byte[] read(String objectKey, long maximumBytes) {
                reads.incrementAndGet();
                return objects.get(objectKey);
            }

            @Override
            public ObjectContent open(String objectKey, long maximumBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String objectKey) {
                throw new UnsupportedOperationException("nothing deletes an example");
            }
        };
    }

    private static DemoProperties properties() {
        return new DemoProperties(
                true, "pairing-code", 1024, 112, 10, 1024, 504, "mock",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe", null);
    }
}
