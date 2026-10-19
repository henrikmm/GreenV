package br.com.greenv.videoapi.domain;

/**
 * What the uploaded file actually is, as opposed to what the phone was asked for.
 *
 * <p>Spelled field for field like {@code PhoneProbe} in
 * {@code measurement/app/src/lib/phone-link.ts}, because the site reads it under those names. An
 * HDR transfer here means washed-out frames later.
 */
public record DemoVideoProbe(
        String codec,
        Integer storedWidth,
        Integer storedHeight,
        int rotation,
        Double avgFps,
        Long videoBitRate,
        boolean hdr,
        String colorTransfer,
        Double durationS) {

    /** Width as the frames come out of ffmpeg, which applies the rotation before any filter. */
    public int displayWidth() {
        return rotation % 180 == 90 ? valueOf(storedHeight) : valueOf(storedWidth);
    }

    public int displayHeight() {
        return rotation % 180 == 90 ? valueOf(storedWidth) : valueOf(storedHeight);
    }

    private static int valueOf(Integer value) {
        return value == null ? 0 : value;
    }
}
