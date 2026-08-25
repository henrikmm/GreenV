package br.com.greenv.frameextractor;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "greenv.extractor.local-polling-enabled=false")
class GreenVFrameExtractorApplicationTest {

    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "greenv-frame-extractor-context-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.extractor.root", TEST_ROOT::toString);
    }

    @Test
    void contextLoadsWithTheLocalAdapter() {
    }
}

