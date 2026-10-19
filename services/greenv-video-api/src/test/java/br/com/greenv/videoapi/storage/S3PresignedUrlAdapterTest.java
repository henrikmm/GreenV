package br.com.greenv.videoapi.storage;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.config.S3StorageProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Signing is local arithmetic; nothing here reaches a bucket. */
class S3PresignedUrlAdapterTest {

    private static final String KEY = "demo/cap-20261024-143000-a1b2/sampled-frames/frame-0001.jpg";

    private final S3StorageProperties properties = new S3StorageProperties(
            "greenv-captures", "auto", "https://account.r2.cloudflarestorage.com", false, "access", "secret");
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
            .endpointOverride(URI.create("https://account.r2.cloudflarestorage.com"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();

    @Test
    void signsAGetForTheKeyAndReusesItForHalfItsLife() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-24T14:30:00Z"));
        S3PresignedUrlAdapter adapter = new S3PresignedUrlAdapter(presigner, properties, clock);

        String first = adapter.sign(KEY, Duration.ofHours(1));
        assertThat(first)
                .startsWith("https://greenv-captures.account.r2.cloudflarestorage.com/" + KEY + "?")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=3600");

        // The SDK stamps a signature with the wall clock to the second, so a second has to pass
        // for a fresh signature to differ from the first; only then does equality prove reuse.
        sleepPastOneSecond();
        clock.now = clock.now.plus(Duration.ofMinutes(29));
        assertThat(adapter.sign(KEY, Duration.ofHours(1))).as("inside half its life").isEqualTo(first);

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(adapter.sign(KEY, Duration.ofHours(1))).as("past half its life").isNotEqualTo(first);
    }

    private static void sleepPastOneSecond() {
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
