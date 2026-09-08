package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.ScalePlan;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real ffmpeg against a fixture that genuinely reorders frames.
 *
 * <p>Explicit selection rests on two assumptions that are cheap to state and expensive to get
 * wrong: that the probe's frame index is the same enumeration the {@code select} filter counts, and
 * that ffmpeg writes one file per selected frame rather than padding the gaps. Both are asserted
 * here against a decoded image, not argued from documentation.
 */
class FfmpegExtractorIntegrationTest {

    private static final int FRAME_COUNT = 30;
    private static final ScalePlan UNSCALED = new ScalePlan(64, 64, false);

    @TempDir
    Path workspace;

    private FfmpegExtractor extractor;

    @BeforeEach
    void setUp() {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg is not installed");
        assumeTrue(commandExists("ffprobe"), "ffprobe is not installed");
        extractor = new FfmpegExtractor(
                new CommandRunner(),
                new ExtractorProperties(workspace.resolve("root"), "ffmpeg", "ffprobe", 300, 3, 1000, false));
    }

    @Test
    void selectsExactlyTheFramesAskedFor() throws Exception {
        Path video = reorderingFixture();
        assumeTrue(containsBFrames(video), "fixture did not encode B-frames; the test would prove nothing");

        List<Integer> wanted = List.of(0, 3, 4, 9, 17, 22, 29);
        List<Path> frames = extractor.extractExact(video, workspace.resolve("out"), wanted, UNSCALED);

        assertThat(frames).hasSameSizeAs(wanted);

        // Each source frame is a distinct shade, so the images prove which frames came out and in
        // what order. A gap-filling sync mode would repeat one value; a wrong enumeration would
        // return them out of order.
        List<Integer> luma = new ArrayList<>();
        for (Path frame : frames) {
            luma.add(meanLuma(frame));
        }
        assertThat(luma).doesNotHaveDuplicates().isSorted();
    }

    /** A hundred-and-twelve-term expression is over ffmpeg's parser depth limit unless balanced. */
    @Test
    void selectsAFullFrameBudgetWithoutOverflowingTheExpressionParser() throws Exception {
        Path video = reorderingFixture();
        List<Integer> wanted = IntStream.range(0, FRAME_COUNT).boxed().toList();

        List<Path> frames = extractor.extractExact(video, workspace.resolve("out"), wanted, UNSCALED);

        assertThat(frames).hasSize(FRAME_COUNT);
        assertThat(SelectExpression.depthOf(SelectExpression.forIndices(
                        IntStream.range(0, 112).boxed().toList())))
                .as("112 terms must stay well under ffmpeg's MAX_DEPTH of 100")
                .isLessThan(20);
    }

    @Test
    void failsLoudlyWhenAFrameCannotBeSelected() throws Exception {
        Path video = reorderingFixture();

        assertThatThrownBy(() -> extractor.extractExact(
                        video, workspace.resolve("out"), List.of(0, 5, FRAME_COUNT + 50), UNSCALED))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("asked for 3 frames and got 2");
    }

    @Test
    void refusesASingleView() throws Exception {
        Path video = reorderingFixture();

        assertThatThrownBy(() ->
                        extractor.extractExact(video, workspace.resolve("out"), List.of(4), UNSCALED))
                .isInstanceOf(ExtractionException.class);
    }

    /** Frame N is a solid shade of 8N, encoded with B-frames so decode order differs from display. */
    private Path reorderingFixture() {
        Path destination = workspace.resolve("reordering.mp4");
        var result = new CommandRunner().run(List.of(
                "ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "color=c=black:s=64x64:r=10:d=3",
                "-vf", "geq=lum=8*N:cb=128:cr=128",
                "-pix_fmt", "yuv420p", "-c:v", "mpeg4", "-bf", "2",
                destination.toString()), Duration.ofMinutes(1));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return destination;
    }

    private boolean containsBFrames(Path video) {
        var result = new CommandRunner().run(List.of(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_frames", "-show_entries", "frame=pict_type",
                "-of", "csv=p=0", video.toString()), Duration.ofMinutes(1));
        return result.exitCode() == 0 && result.stdout().contains("B");
    }

    private static int meanLuma(Path image) throws Exception {
        var read = ImageIO.read(image.toFile());
        long total = 0;
        for (int y = 0; y < read.getHeight(); y++) {
            for (int x = 0; x < read.getWidth(); x++) {
                total += read.getRGB(x, y) & 0xFF;
            }
        }
        return (int) (total / ((long) read.getWidth() * read.getHeight()));
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
