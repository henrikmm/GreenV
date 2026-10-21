package br.com.greenv.videoapi.domain;

import java.util.List;

/**
 * The names a demo capture's timeline is written in.
 *
 * <p>They are the phone ↔ gateway contract's names (version 1, {@code docs/NEXT-DEMO.md}), so the
 * phone that already reads them from the Mac reads them from the API unchanged. The difference is
 * who writes them: on the Mac the studio reported most of them from the browser, here the server
 * writes every one. {@code fetched} therefore means the artifacts are published and signable,
 * ready for the site, rather than "the browser built the cloud", and {@code loaded} and
 * {@code loading-model} no longer occur.
 *
 * <p>A {@code failed} line carries {@code failedIn}: the phase that was last written before the
 * failure, which is the step that was in progress when it happened.
 */
public final class DemoPhases {

    public static final String CREATED = "created";
    public static final String UPLOADING = "uploading";
    public static final String RECEIVED = "received";
    public static final String EXTRACTING = "extracting";
    public static final String EXTRACTED = "extracted";
    public static final String UPLOADING_FRAMES = "uploading-frames";
    /** The depth job is queued at RunPod ({@code IN_QUEUE}): a worker is starting or busy. */
    public static final String WAKING = "waking";
    /** The depth job is running on a GPU ({@code IN_PROGRESS}). */
    public static final String INFERRING = "inferring";
    public static final String INFERRED = "inferred";
    public static final String FETCHED = "fetched";
    public static final String FAILED = "failed";
    /**
     * The GPU machine is sampling the video ({@code GREENV_DEMO_SAMPLE_ON=gpu}): the job is
     * running and its first step is ffmpeg. Not {@code extracting}, which is the API's own
     * sampling and is picked up by the API's sampling lane.
     */
    public static final String SAMPLING = "sampling";
    /** The GPU machine has sampled the video; the detail says how, and in how long. */
    public static final String SAMPLED = "sampled";

    /** States the pipeline picks up before a depth job exists: the video is in storage. */
    public static final List<String> BEFORE_JOB = List.of(RECEIVED, EXTRACTING, EXTRACTED, UPLOADING_FRAMES);

    /** States the pipeline picks up while a depth job exists or its result is being checked. */
    public static final List<String> WITH_JOB = List.of(WAKING, SAMPLING, SAMPLED, INFERRING, INFERRED);

    private DemoPhases() {
    }
}
