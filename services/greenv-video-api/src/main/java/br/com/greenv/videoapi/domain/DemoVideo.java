package br.com.greenv.videoapi.domain;

/**
 * The video a phone sent for one demo capture, and how long it took to arrive.
 *
 * <p>{@code transferMs} runs from the first byte read to the last, on this server, so it measures
 * the network rather than the phone's encoder. {@code probe} is null until the pipeline has
 * opened the file.
 */
public record DemoVideo(
        String objectKey,
        String contentType,
        long bytes,
        String sha256,
        long transferMs,
        DemoVideoProbe probe) {

    public Double megabytesPerSecond() {
        if (transferMs <= 0) {
            return null;
        }
        return Math.round((bytes / 1e6) / (transferMs / 1000.0) * 100) / 100.0;
    }

    public DemoVideo withProbe(DemoVideoProbe value) {
        return new DemoVideo(objectKey, contentType, bytes, sha256, transferMs, value);
    }
}
