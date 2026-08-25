package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobControllerIntegrationTest {

    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "greenv-video-api-http-" + UUID.randomUUID());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add("greenv.pipeline.saved-root", () -> TEST_ROOT.resolve("saved").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void acceptsACompleteHttpUploadLifecycle() throws Exception {
        byte[] video = "synthetic video".getBytes(StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port + "/v1/jobs";

        HttpResponse<String> created = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {
                                  "fileName": "road.mp4",
                                  "contentType": "video/mp4",
                                  "sizeBytes": %d,
                                  "requestedFps": 10,
                                  "maxFrames": 100,
                                  "longEdge": 1024
                                }
                                """.formatted(video.length)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(created.statusCode()).isEqualTo(201);
        String jobId = objectMapper.readTree(created.body()).path("jobId").asString();

        HttpResponse<Void> uploaded = client.send(
                HttpRequest.newBuilder(URI.create(base + "/" + jobId + "/source"))
                        .header("content-type", "video/mp4")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(video))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isEqualTo(204);

        HttpResponse<String> completed = client.send(
                HttpRequest.newBuilder(URI.create(base + "/" + jobId + "/complete"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(completed.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(completed.body()).path("state").asString()).isEqualTo("queued");

        HttpResponse<String> status = client.send(
                HttpRequest.newBuilder(URI.create(base + "/" + jobId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(status.body()).path("actualSizeBytes").asLong())
                .isEqualTo(video.length);
    }
}

