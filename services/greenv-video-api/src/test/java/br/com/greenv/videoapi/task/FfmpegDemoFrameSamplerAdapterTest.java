package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.DemoVideoProbe;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The planning half of the sampler, which needs no ffmpeg. The command itself is not run here:
 * the host this suite runs on has no ffmpeg, and the runtime image installs it.
 */
class FfmpegDemoFrameSamplerAdapterTest {

    @Test
    void keepsTheAskedRateWhileTheBudgetCoversTheClip() {
        var plan = FfmpegDemoFrameSamplerAdapter.plan(10, 8.0, 112);
        assertThat(plan.count()).isEqualTo(80);
        assertThat(plan.effectiveFps()).isEqualTo(10.0);
        assertThat(plan.capped()).isFalse();
    }

    @Test
    void spreadsTheBudgetOverALongClipInsteadOfSpendingItOnItsStart() {
        var plan = FfmpegDemoFrameSamplerAdapter.plan(10, 20.0, 112);
        assertThat(plan.count()).isEqualTo(112);
        assertThat(plan.effectiveFps()).isEqualTo(5.6);
        assertThat(plan.capped()).isTrue();
        assertThat(plan.requestedCount()).isEqualTo(200);
    }

    @Test
    void scalesThePortraitFrameAsDisplayedAndNeverEnlarges() {
        var portrait = FfmpegDemoFrameSamplerAdapter.scale(1080, 1920, 1024);
        assertThat(portrait.width()).isEqualTo(576);
        assertThat(portrait.height()).isEqualTo(1024);
        assertThat(portrait.scaled()).isTrue();
        assertThat(FfmpegDemoFrameSamplerAdapter.scale(640, 480, 1024).scaled()).isFalse();
    }

    @Test
    void readsAPhoneProbeIncludingItsRotation() {
        var root = JsonMapper.builder().build().readTree("""
                {"streams":[{"codec_name":"hevc","width":1920,"height":1080,"avg_frame_rate":"30000/1001",
                  "bit_rate":"12000000","color_transfer":"arib-std-b67",
                  "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}],
                 "format":{"duration":"8.008000"}}
                """);

        DemoVideoProbe probe = FfmpegDemoFrameSamplerAdapter.parseProbe(root);

        assertThat(probe.codec()).isEqualTo("hevc");
        assertThat(probe.rotation()).isEqualTo(270);
        assertThat(probe.displayWidth()).isEqualTo(1080);
        assertThat(probe.displayHeight()).isEqualTo(1920);
        assertThat(probe.avgFps()).isEqualTo(29.97);
        assertThat(probe.videoBitRate()).isEqualTo(12_000_000L);
        assertThat(probe.hdr()).isTrue();
        assertThat(probe.durationS()).isEqualTo(8.008);
    }
}
