package com.universe.media.infrastructure.hls.poc;

import com.universe.media.infrastructure.hls.BoundedSubprocessRunner;
import com.universe.media.infrastructure.hls.FfmpegHlsPackager;
import com.universe.media.infrastructure.hls.FfprobeMediaSourceProbe;
import com.universe.media.infrastructure.hls.HlsLadderPlanner;
import com.universe.media.infrastructure.hls.LocalHlsPackageVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class HlsLadderPlannerAndVerifierUnitTest {

    private final HlsLadderPlanner planner = new HlsLadderPlanner();

    private static HlsLadderPlanner.ProbedVideoSource source(
            int width,
            int height,
            int rotation,
            String sar,
            double fps,
            String pixFmt,
            int bitsPerSample,
            String transfer,
            String primaries,
            boolean hasAudio,
            int channels
    ) {
        int rawW = width;
        int rawH = height;
        int rot = ((rotation % 360) + 360) % 360;
        boolean swap = rot == 90 || rot == 270;
        int dispW = swap ? height : width;
        int dispH = swap ? width : height;
        return new HlsLadderPlanner.ProbedVideoSource(
                rawW,
                rawH,
                rot,
                sar,
                "",
                dispW,
                dispH,
                "24000/1001",
                "24000/1001",
                fps,
                24.0,
                "h264",
                pixFmt,
                bitsPerSample,
                "bt709",
                transfer,
                primaries,
                hasAudio,
                hasAudio ? 1 : 0,
                hasAudio ? "aac" : null,
                channels,
                hasAudio ? 48_000 : 0,
                hasAudio ? "und" : null
        );
    }

    @Test
    @DisplayName("Selects exact 720p / 1080p / 2160p tiers without 480p or unintended upscaling")
    void shouldSelectApprovedTiersWithoutUpscalingOrDefault480p() {
        HlsLadderPlanner.HlsPackagingPlan p720 = planner.plan(
                source(1280, 720, 0, "1:1", 23.976, "yuv420p", 8, "bt709", "bt709", true, 2),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(p720.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label).containsExactly("720p");
        assertThat(p720.includeAudio()).isTrue();
        assertThat(p720.gopSizeFrames()).isEqualTo(144);

        HlsLadderPlanner.HlsPackagingPlan p1080 = planner.plan(
                source(1920, 1080, 0, "1:1", 29.97, "yuv420p", 8, "bt709", "bt709", true, 6),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(p1080.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label).containsExactly("720p", "1080p");
        assertThat(p1080.audioChannels()).isEqualTo(2);

        HlsLadderPlanner.HlsPackagingPlan p4k = planner.plan(
                source(3840, 2160, 0, "1:1", 24.0, "yuv420p", 8, "bt709", "bt709", true, 1),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(p4k.videoTiers()).extracting(HlsLadderPlanner.VideoTierSpec::label)
                .containsExactly("720p", "1080p", "2160p");
        assertThat(p4k.audioChannels()).isEqualTo(1);
    }

    @Test
    @DisplayName("Preserves aspect ratio and orientation for cinema 2.39:1, vertical portrait, and rotated sources")
    void shouldPreserveAspectRatioAndOrientationWithoutStretching() {
        // 1920x804 (2.39:1 widescreen)
        HlsLadderPlanner.HlsPackagingPlan scopePlan = planner.plan(
                source(1920, 804, 0, "1:1", 24.0, "yuv420p", 8, "bt709", "bt709", true, 2),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(scopePlan.videoTiers()).hasSize(2);
        assertThat(scopePlan.videoTiers().get(0).width()).isEqualTo(1280);
        assertThat(scopePlan.videoTiers().get(0).height()).isEqualTo(536);
        assertThat(scopePlan.videoTiers().get(1).width()).isEqualTo(1920);
        assertThat(scopePlan.videoTiers().get(1).height()).isEqualTo(804);

        // 1920x1080 rotated 90 degrees -> effective portrait 1080x1920
        HlsLadderPlanner.HlsPackagingPlan portraitPlan = planner.plan(
                source(1920, 1080, 90, "1:1", 30.0, "yuv420p", 8, "bt709", "bt709", false, 0),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(portraitPlan.includeAudio()).isFalse();
        assertThat(portraitPlan.videoTiers().get(0).width()).isEqualTo(720);
        assertThat(portraitPlan.videoTiers().get(0).height()).isEqualTo(1280);
        assertThat(portraitPlan.videoTiers().get(1).width()).isEqualTo(1080);
        assertThat(portraitPlan.videoTiers().get(1).height()).isEqualTo(1920);
    }

    @Test
    @DisplayName("Below-720p uses safe source fallback without upscaling or rejects explicitly; HDR/10-bit fails explicitly")
    void shouldEnforceBelow720AndHdrPolicies() {
        HlsLadderPlanner.ProbedVideoSource sd = source(854, 480, 0, "1:1", 24.0, "yuv420p", 8, "bt709", "bt709", true, 2);
        HlsLadderPlanner.HlsPackagingPlan fallback = planner.plan(
                sd, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        assertThat(fallback.below720FallbackApplied()).isTrue();
        assertThat(fallback.videoTiers()).hasSize(1);
        assertThat(fallback.videoTiers().get(0).label()).isEqualTo("source");
        assertThat(fallback.videoTiers().get(0).width()).isEqualTo(854);
        assertThat(fallback.videoTiers().get(0).height()).isEqualTo(480);

        assertThatThrownBy(() -> planner.plan(
                sd, 6, HlsLadderPlanner.Below720Policy.REJECT_BELOW_720P, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        )).isInstanceOf(HlsLadderPlanner.UnsupportedHlsSourceException.class);

        HlsLadderPlanner.ProbedVideoSource hdr = source(3840, 2160, 0, "1:1", 24.0, "yuv420p10le", 10, "smpte2084", "bt2020", true, 2);
        assertThatThrownBy(() -> planner.plan(
                hdr, 6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        )).isInstanceOf(HlsLadderPlanner.UnsupportedHlsSourceException.class)
                .hasMessageContaining("10-bit/HDR");
    }

    @Test
    @DisplayName("FFmpeg command builder maps ONE shared audio group or cleanly omits audio for video-only inputs")
    void shouldBuildDeterministicCommandWithSharedAudioOrVideoOnly() {
        FfmpegHlsPackager packager = new FfmpegHlsPackager(
                "ffmpeg",
                new BoundedSubprocessRunner(),
                4,
                Duration.ofMinutes(60),
                "veryfast"
        );

        HlsLadderPlanner.HlsPackagingPlan withAudio = planner.plan(
                source(1920, 1080, 0, "1:1", 23.976, "yuv420p", 8, "bt709", "bt709", true, 2),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        List<String> cmdWithAudio = packager.buildFfmpegCommand(Path.of("in.mp4"), withAudio);
        int mapIdx = cmdWithAudio.indexOf("-var_stream_map");
        assertThat(mapIdx).isGreaterThan(0);
        assertThat(cmdWithAudio.get(mapIdx + 1))
                .isEqualTo("v:0,agroup:aud,name:video/720p v:1,agroup:aud,name:video/1080p a:0,agroup:aud,name:audio/main,default:yes");
        assertThat(cmdWithAudio).contains("fmp4", "vod", "independent_segments", "init.mp4", "%v/seg_%05d.m4s");

        HlsLadderPlanner.HlsPackagingPlan noAudio = planner.plan(
                source(1920, 1080, 0, "1:1", 23.976, "yuv420p", 8, "bt709", "bt709", false, 0),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        List<String> cmdNoAudio = packager.buildFfmpegCommand(Path.of("in.mp4"), noAudio);
        int noAudioMapIdx = cmdNoAudio.indexOf("-var_stream_map");
        assertThat(cmdNoAudio.get(noAudioMapIdx + 1))
                .isEqualTo("v:0,name:video/720p v:1,name:video/1080p");
        assertThat(cmdNoAudio).doesNotContain("aac");
    }

    @Test
    @DisplayName("Verifier rejects missing/truncated/corrupt files, path escapes, incomplete VOD, and multi-audio duplication")
    void shouldRejectInvalidOrTamperedHlsPackages(@TempDir Path tempDir) throws Exception {
        HlsLadderPlanner.HlsPackagingPlan plan = planner.plan(
                source(1280, 720, 0, "1:1", 24.0, "yuv420p", 8, "bt709", "bt709", true, 2),
                6, HlsLadderPlanner.Below720Policy.SOURCE_FALLBACK_NO_UPSCALE, HlsLadderPlanner.HdrPolicy.REJECT_EXPLICIT
        );
        FfprobeMediaSourceProbe stubProbe = new FfprobeMediaSourceProbe("ffprobe", new BoundedSubprocessRunner(), Duration.ofSeconds(60)) {
            @Override
            public HlsPlaylistProbe probeHlsMediaPlaylist(Path playlistPath) {
                String p = playlistPath.toString().replace('\\', '/');
                if (p.contains("audio/main")) {
                    return new HlsPlaylistProbe(1, "audio", "aac", 0, 0, 48_000, 2, 6.0, 280);
                }
                return new HlsPlaylistProbe(1, "video", "h264", 1280, 720, 0, 0, 6.0, 144);
            }
        };
        LocalHlsPackageVerifier verifier = new LocalHlsPackageVerifier(stubProbe);

        Path pkg = tempDir.resolve("pkg");
        writeMinimalValid720pPackage(pkg);
        LocalHlsPackageVerifier.LocalHlsVerificationReport validReport = verifier.verify(pkg, plan);
        assertThat(validReport.objectCount()).isEqualTo(7);
        assertThat(validReport.renditionsSummaryJson().length()).isLessThanOrEqualTo(2000);

        // 1. Missing segment fails
        Path seg = pkg.resolve("video/720p/seg_00000.m4s");
        byte[] origSeg = Files.readAllBytes(seg);
        Files.delete(seg);
        assertThatThrownBy(() -> verifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("Missing or truncated");

        // 2. Truncated / corrupt segment fails
        Files.write(seg, new byte[]{0, 0, 0, 8, 'x', 'x', 'x', 'x'});
        assertThatThrownBy(() -> verifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class);
        Files.write(seg, origSeg);

        // 3. Missing #EXT-X-ENDLIST fails
        Path videoPl = pkg.resolve("video/720p/playlist.m3u8");
        String origVideoPl = Files.readString(videoPl);
        Files.writeString(videoPl, origVideoPl.replace("#EXT-X-ENDLIST", ""));
        assertThatThrownBy(() -> verifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("#EXT-X-ENDLIST");
        Files.writeString(videoPl, origVideoPl);

        // 4. Path traversal outside root fails
        Files.writeString(videoPl, origVideoPl.replace("seg_00000.m4s", "../../../escape.m4s"));
        assertThatThrownBy(() -> verifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("escaping package root");
        Files.writeString(videoPl, origVideoPl);

        // 5. Duplicate audio rendition group rejected
        Path master = pkg.resolve("master.m3u8");
        String origMaster = Files.readString(master);
        Files.writeString(master, origMaster.replace(
                "#EXT-X-STREAM-INF",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"group_aud\",NAME=\"dup\",URI=\"audio/main/playlist.m3u8\"\n#EXT-X-STREAM-INF"
        ));
        assertThatThrownBy(() -> verifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("Expected exact 1 shared audio");
        Files.writeString(master, origMaster);

        // 6. Audio leaked into video playlist rejected
        LocalHlsPackageVerifier leakyAudioVerifier = new LocalHlsPackageVerifier(
                new FfprobeMediaSourceProbe("ffprobe", new BoundedSubprocessRunner(), Duration.ofSeconds(60)) {
                    @Override
                    public HlsPlaylistProbe probeHlsMediaPlaylist(Path playlistPath) {
                        return new HlsPlaylistProbe(2, "video", "h264", 1280, 720, 0, 0, 6.0, 144);
                    }
                }
        );
        assertThatThrownBy(() -> leakyAudioVerifier.verify(pkg, plan))
                .isInstanceOf(LocalHlsPackageVerifier.HlsPackageVerificationException.class)
                .hasMessageContaining("exactly 1 stream");
    }

    @Test
    @DisplayName("Local FFmpeg PoC suite is gated behind @Tag('local-ffmpeg-poc') and @EnabledIfSystemProperty for CI safety")
    void shouldEnforceOptInCiGuardOnLocalFfmpegExecutionSuite() {
        Tag tag = HlsFfmpegLocalPocExecutionTest.class.getAnnotation(Tag.class);
        assertThat(tag).isNotNull();
        assertThat(tag.value()).isEqualTo("local-ffmpeg-poc");

        EnabledIfSystemProperty optIn = HlsFfmpegLocalPocExecutionTest.class.getAnnotation(EnabledIfSystemProperty.class);
        assertThat(optIn).isNotNull();
        assertThat(optIn.named()).isEqualTo(HlsFfmpegLocalPocExecutionTest.OPT_IN_PROPERTY);
        assertThat(optIn.matches()).isEqualTo("(?i)true");
    }

    public static void writeMinimalValid720pPackage(Path root) throws Exception {
        Files.createDirectories(root.resolve("video/720p"));
        Files.createDirectories(root.resolve("audio/main"));
        Files.writeString(root.resolve("master.m3u8"), """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="group_aud",NAME="main",DEFAULT=YES,CHANNELS="2",URI="audio/main/playlist.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2",AUDIO="group_aud"
                video/720p/playlist.m3u8
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("video/720p/playlist.m3u8"), """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:6
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-MAP:URI="init_0.mp4"
                #EXTINF:6.000000,
                seg_00000.m4s
                #EXT-X-ENDLIST
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("audio/main/playlist.m3u8"), """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:6
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-MAP:URI="init_1.mp4"
                #EXTINF:6.000000,
                seg_00000.m4s
                #EXT-X-ENDLIST
                """, StandardCharsets.UTF_8);
        Files.write(root.resolve("video/720p/init_0.mp4"), bmffPair("ftyp", "moov"));
        Files.write(root.resolve("video/720p/seg_00000.m4s"), bmffPair("moof", "mdat"));
        Files.write(root.resolve("audio/main/init_1.mp4"), bmffPair("ftyp", "moov"));
        Files.write(root.resolve("audio/main/seg_00000.m4s"), bmffPair("moof", "mdat"));
    }

    private static byte[] bmffPair(String box1, String box2) {
        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putInt(16).put(box1.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        buf.putInt(16).put(box2.getBytes(StandardCharsets.US_ASCII)).putLong(0L);
        return buf.array();
    }
}
