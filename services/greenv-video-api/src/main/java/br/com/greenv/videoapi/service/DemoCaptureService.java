package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import br.com.greenv.videoapi.domain.DemoPhases;
import br.com.greenv.videoapi.domain.DemoRun;
import br.com.greenv.videoapi.domain.DemoUploadReceipt;
import br.com.greenv.videoapi.domain.DemoVideo;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import br.com.greenv.videoapi.port.DemoCaptureUseCase;
import br.com.greenv.videoapi.port.ObjectUrlSigner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The demo's front door: the phone announces a capture and sends its video, and anyone may read
 * what became of it.
 *
 * <p>This is the phone ↔ gateway contract, version 1, served by the API instead of by the Mac.
 * The phone only changes its base URL, so every response keeps the shape the Mac gave it — and the
 * Mac's intake ({@code measurement/app/vite-plugins/phone-intake.mjs}) stays the fallback.
 *
 * <p>The pairing code is a speed bump, not an identity: one shared secret that the operator types
 * into the phone. It guards the only two calls that put anything on this server. It is compared
 * in constant time, so the time an answer takes says nothing about how much of a guess was right.
 */
@Service
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoCaptureService implements DemoCaptureUseCase {

    private static final Logger log = LoggerFactory.getLogger(DemoCaptureService.class);

    public static final String META_SCHEMA = "verge-phone-capture/1";
    public static final int MAX_META_BYTES = 64 * 1024;
    public static final int MAX_LIST = 100;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern ID_PATTERN = Pattern.compile("^cap-\\d{8}-\\d{6}-[a-z0-9]{4}$");
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final DateTimeFormatter ID_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Map<String, String> VIDEO_TYPES = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm");
    private static final int BUFFER_BYTES = 256 * 1024;
    /** Above anything the demo stores: a 95 MiB video, or a reconstruction of a few tens of MB. */
    private static final long MAX_OBJECT_BYTES = 1024L * 1024 * 1024;

    private final DemoCaptureStore store;
    private final CaptureObjectStorage storage;
    private final ObjectUrlSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DemoProperties properties;
    private final byte[] pairingCode;
    private final SecureRandom random = new SecureRandom();

    public DemoCaptureService(
            DemoCaptureStore store,
            CaptureObjectStorage storage,
            ObjectUrlSigner signer,
            ObjectMapper objectMapper,
            Clock clock,
            DemoProperties properties) {
        this.store = store;
        this.storage = storage;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.pairingCode = properties.pairingCode().strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean authorised(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER_PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, pairingCode);
    }

    /** 401 when no code was presented at all, 403 when the one presented is wrong. */
    private void requireAuthorised(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || authorization.substring(BEARER_PREFIX.length()).isBlank()) {
            throw new ApplicationException(
                    FailureKind.UNAUTHORIZED, "pairing_code_required", "missing pairing code");
        }
        if (!authorised(authorization)) {
            throw new ApplicationException(
                    FailureKind.FORBIDDEN, "pairing_code_wrong", "wrong pairing code");
        }
    }

    @Override
    public DemoCapture announce(String authorization, InputStream metadata) {
        requireAuthorised(authorization);
        JsonNode meta = readMeta(metadata);
        Instant capturedAt = validateMeta(meta);
        Instant now = clock.instant();
        DemoCapture capture = DemoCapture.announced(newCaptureId(now), meta, capturedAt, now);
        store.insert(capture);
        JsonNode settings = meta.path("settings");
        log.info("[demo] {} announced · {}@{} · {} ms recorded",
                capture.id(),
                settings.path("resolution").asString("?"),
                settings.path("fps").asString("?"),
                meta.path("durationMs").asString("?"));
        return capture;
    }

    @Override
    public DemoUploadReceipt receiveVideo(
            String authorization,
            String captureId,
            String contentType,
            long contentLength,
            InputStream body) {
        requireAuthorised(authorization);
        DemoCapture capture = existing(captureId);
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase();
        String extension = VIDEO_TYPES.get(mediaType);
        if (extension == null) {
            throw new ApplicationException(
                    FailureKind.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported_video_type",
                    "unsupported content-type " + contentType + "; send video/mp4, video/quicktime or video/webm");
        }
        if (contentLength <= 0) {
            throw new ApplicationException(
                    FailureKind.LENGTH_REQUIRED, "content_length_required", "content-length is required");
        }
        if (contentLength > properties.maxVideoBytes()) {
            throw new ApplicationException(
                    FailureKind.PAYLOAD_TOO_LARGE,
                    "video_too_large",
                    "video is " + contentLength + " bytes, over the " + properties.maxVideoBytes() + " limit");
        }
        Instant startedAt = clock.instant();
        Map<String, Object> expected = Map.of("bytesExpected", contentLength);
        if (!store.beginUpload(captureId, DemoPhase.of(DemoPhases.UPLOADING, capture.createdAt(), startedAt, expected))) {
            throw new ApplicationException(
                    FailureKind.CONFLICT, "video_already_received", "video already received");
        }

        Path staged = null;
        try {
            staged = Files.createTempFile("greenv-demo-", "." + extension);
            Staged received = stage(body, staged, contentLength);
            String key = DemoCapture.prefix(captureId) + "video." + extension;
            try (InputStream input = Files.newInputStream(staged)) {
                storage.put(key, input, received.sha256(), properties.maxVideoBytes());
            }
            Instant finishedAt = clock.instant();
            long transferMs = Math.max(0, finishedAt.toEpochMilli()
                    - (received.firstByteAt() == null ? startedAt : received.firstByteAt()).toEpochMilli());
            DemoVideo video = new DemoVideo(key, mediaType, received.bytes(), received.sha256(), transferMs, null);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("bytes", received.bytes());
            detail.put("transferMs", transferMs);
            detail.put("megabytesPerSecond", video.megabytesPerSecond());
            DemoCapture current = existing(captureId);
            store.save(
                    current.withVideo(video),
                    DemoPhase.of(DemoPhases.RECEIVED, current.createdAt(), finishedAt, detail),
                    null);
            log.info("[demo] {} {} MB in {} ms ({} MB/s)",
                    captureId, String.format(java.util.Locale.ROOT, "%.1f", received.bytes() / 1e6),
                    transferMs, video.megabytesPerSecond());
            return new DemoUploadReceipt(captureId, received.bytes(), received.sha256(), transferMs);
        } catch (IOException | RuntimeException exception) {
            failUpload(captureId, exception);
            if (exception instanceof ApplicationException application) {
                throw application;
            }
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR, "video_upload_failed", "could not store the video: " + exception.getMessage());
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // The operating system reclaims an abandoned temporary file.
                }
            }
        }
    }

    /**
     * The upload began and did not finish. The capture is marked failed, so the site shows what
     * happened rather than a capture stuck in {@code uploading}. It still takes the video again
     * ({@link DemoCaptureStore#beginUpload}): the phone keeps the capture's id and retries a
     * broken upload under it, exactly as it does against the Mac.
     */
    private void failUpload(String captureId, Exception cause) {
        try {
            DemoCapture current = existing(captureId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            detail.put("failedIn", DemoPhases.UPLOADING);
            store.save(current, DemoPhase.of(DemoPhases.FAILED, current.createdAt(), clock.instant(), detail), null);
        } catch (RuntimeException secondary) {
            log.warn("[demo] {} could not be marked failed after its upload broke: {}", captureId, secondary.getMessage());
        }
    }

    @Override
    public DemoCapture capture(String captureId) {
        return existing(captureId);
    }

    @Override
    public List<DemoCapture> latest(int limit) {
        return store.latest(Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LIST)));
    }

    @Override
    public void delete(String authorization, String captureId) {
        requireAuthorised(authorization);
        DemoCapture capture = existing(captureId);
        if (DemoPhases.BEFORE_JOB.contains(capture.state())
                || DemoPhases.WITH_JOB.contains(capture.state())
                || DemoPhases.UPLOADING.equals(capture.state())) {
            throw new ApplicationException(
                    FailureKind.CONFLICT,
                    "capture_in_progress",
                    "capture is " + capture.state() + "; delete it once it is fetched or failed");
        }
        // Objects first and the row last, as the retention cleanup does: a delete cut short is
        // finished by trying again, or by that cleanup, instead of leaving files nothing names.
        DemoCleanupService.deleteObjects(storage, DemoCleanupService.objectKeys(capture));
        store.delete(capture.id());
    }

    @Override
    public DemoRun run(String captureId) {
        DemoCapture capture = existing(captureId);
        List<String> frames = capture.frameKeys().stream()
                .map(key -> signer.sign(key, properties.signedUrlTtl()))
                .toList();
        JsonNode manifest = DemoPhases.FETCHED.equals(capture.state()) && capture.manifest() != null
                ? signedManifest(capture)
                : null;
        return new DemoRun(frames, manifest, capture.gpu(), capture.gpuSeconds());
    }

    /**
     * The handler's manifest with every artifact link signed now, from its object key. The
     * handler's own links are twelve-hour links the API never hands out; a key outside this
     * capture's prefix is dropped rather than signed, so a manifest can never be used to read
     * another object.
     */
    private JsonNode signedManifest(DemoCapture capture) {
        ObjectNode manifest = (ObjectNode) capture.manifest().deepCopy();
        JsonNode artifacts = manifest.path("artifacts");
        if (artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                if (!(artifact instanceof ObjectNode node)) {
                    continue;
                }
                String key = node.path("object_key").asString("");
                if (key.startsWith(DemoCapture.prefix(capture.id())) && !key.contains("..")) {
                    node.put("url", signer.sign(key, properties.signedUrlTtl()));
                } else {
                    node.putNull("url");
                }
            }
        }
        return manifest;
    }

    @Override
    public CaptureObjectStorage.ObjectContent openObject(String objectKey) {
        if (objectKey == null || !objectKey.startsWith("demo/") || objectKey.contains("..")) {
            throw new ApplicationException(
                    FailureKind.NOT_FOUND, "demo_object_not_found", "not a demo object");
        }
        return storage.open(objectKey, MAX_OBJECT_BYTES);
    }

    private DemoCapture existing(String captureId) {
        if (captureId == null || !ID_PATTERN.matcher(captureId).matches()) {
            throw new ApplicationException(FailureKind.NOT_FOUND, "demo_capture_not_found", "no such capture");
        }
        return store.find(captureId).orElseThrow(() -> new ApplicationException(
                FailureKind.NOT_FOUND, "demo_capture_not_found", "no such capture"));
    }

    private JsonNode readMeta(InputStream metadata) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        try {
            int read;
            while ((read = metadata.read(chunk)) != -1) {
                total += read;
                if (total > MAX_META_BYTES) {
                    throw new ApplicationException(
                            FailureKind.PAYLOAD_TOO_LARGE, "metadata_too_large", "metadata too large");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException exception) {
            throw new ApplicationException(FailureKind.INVALID_INPUT, "metadata_unreadable", "metadata could not be read");
        }
        try {
            return objectMapper.readTree(buffer.toByteArray());
        } catch (JacksonException exception) {
            throw new ApplicationException(FailureKind.INVALID_INPUT, "metadata_not_json", "metadata is not JSON");
        }
    }

    /** {@code validateMeta} of the Mac intake: refuses anything else, keeps unknown fields. */
    private static Instant validateMeta(JsonNode meta) {
        if (meta == null || !meta.isObject()) {
            throw invalidMeta("metadata must be a JSON object");
        }
        if (!META_SCHEMA.equals(meta.path("schema").asString(""))) {
            throw invalidMeta("metadata.schema must be \"" + META_SCHEMA + "\"");
        }
        Instant capturedAt = timestamp(meta.path("capturedAt"));
        if (capturedAt == null) {
            throw invalidMeta("metadata.capturedAt must be an ISO-8601 timestamp");
        }
        JsonNode settings = meta.get("settings");
        if (settings != null && !settings.isObject()) {
            throw invalidMeta("metadata.settings must be an object");
        }
        return capturedAt;
    }

    private static Instant timestamp(JsonNode value) {
        if (!value.isString()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException offsetMissing) {
            try {
                // An ISO date-time without an offset is read as UTC rather than refused: the phone
                // sends UTC, and the field is a record of the phone's clock, not a join key.
                return java.time.LocalDateTime.parse(value.asString()).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    private static ApplicationException invalidMeta(String message) {
        return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_metadata", message);
    }

    /** {@code cap-YYYYMMDD-HHMMSS-xxxx} in UTC, the shape the Mac gateway gives out. */
    private String newCaptureId(Instant now) {
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return "cap-" + ID_STAMP.format(now) + "-" + suffix;
    }

    /**
     * Streams the body to disk, hashing as it goes, and refuses a body that does not match the
     * length it declared. Never holds a whole video in memory: the API has 2 GiB and a video is
     * up to 95 MiB.
     */
    private Staged stage(InputStream body, Path destination, long declaredLength) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
        long bytes = 0;
        Instant firstByteAt = null;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (OutputStream output = new DigestOutputStream(Files.newOutputStream(destination), digest)) {
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (firstByteAt == null && read > 0) {
                    firstByteAt = clock.instant();
                }
                bytes += read;
                if (bytes > declaredLength) {
                    throw new ApplicationException(
                            FailureKind.INVALID_INPUT,
                            "video_length_mismatch",
                            "received more than the " + declaredLength + " bytes declared");
                }
                output.write(buffer, 0, read);
            }
        }
        if (bytes != declaredLength) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "video_length_mismatch",
                    "received " + bytes + " of " + declaredLength + " bytes");
        }
        return new Staged(HexFormat.of().formatHex(digest.digest()), bytes, firstByteAt);
    }

    private record Staged(String sha256, long bytes, Instant firstByteAt) {
    }
}
