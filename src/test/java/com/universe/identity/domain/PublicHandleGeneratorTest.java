package com.universe.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicHandleGeneratorTest {

    private static final UUID TEST_USER_ID = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");

    @Test
    @DisplayName("attempt 0 trả về base candidate ban đầu")
    void shouldReturnBaseCandidateForAttempt0() {
        String handle = PublicHandleGenerator.candidateForAttempt(
                "Athena Dev",
                TEST_USER_ID,
                0
        );

        assertThat(handle).isEqualTo("athena_dev");
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @Test
    @DisplayName("attempt 1 trả về candidate kèm 4 ký tự discriminator từ UUID")
    void shouldReturn4CharDiscriminatorForAttempt1() {
        String handle = PublicHandleGenerator.candidateForAttempt(
                "Athena Dev",
                TEST_USER_ID,
                1
        );

        // UUID hex starts with abcdef01 -> 4 chars = abcd
        assertThat(handle).isEqualTo("athena_dev_abcd");
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @Test
    @DisplayName("attempt 2 trả về candidate kèm 8 ký tự discriminator từ UUID")
    void shouldReturn8CharDiscriminatorForAttempt2() {
        String handle = PublicHandleGenerator.candidateForAttempt(
                "Athena Dev",
                TEST_USER_ID,
                2
        );

        // UUID hex starts with abcdef0123456789... -> 8 chars = abcdef01
        assertThat(handle).isEqualTo("athena_dev_abcdef01");
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @Test
    @DisplayName("Cắt bớt độ dài base candidate khi thêm discriminator nếu tổng độ dài vượt quá 40")
    void shouldTruncateBaseCandidateWhenAppendingDiscriminator() {
        String longName = "this_is_a_very_long_display_name_40_char";

        String handle = PublicHandleGenerator.candidateForAttempt(
                longName,
                TEST_USER_ID,
                1
        );

        assertThat(handle.length()).isLessThanOrEqualTo(40);
        assertThat(handle).endsWith("_abcd");
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @Test
    @DisplayName("attempt từ 0 đến 10 sinh các candidate hợp lệ tương ứng")
    void shouldGenerateValidCandidatesForAttempts0Through10() {
        for (int attempt = 0; attempt <= PublicHandleGenerator.MAX_COLLISION_ATTEMPTS; attempt++) {
            String candidate = PublicHandleGenerator.candidateForAttempt("Athena Dev", TEST_USER_ID, attempt);
            assertThat(PublicHandleNormalizer.isValidHandle(candidate)).isTrue();
        }
    }

    @Test
    @DisplayName("attempt > MAX_COLLISION_ATTEMPTS trả về ultimate fallback u_ + 32 hex UUID")
    void shouldReturnUltimateUuidFallbackWhenAttemptExceedsMaxCollisionAttempts() {
        String handle = PublicHandleGenerator.candidateForAttempt(
                "Athena Dev",
                TEST_USER_ID,
                PublicHandleGenerator.MAX_COLLISION_ATTEMPTS + 1
        );

        assertThat(handle).startsWith("u_");
        assertThat(handle).isEqualTo("u_abcdef0123456789abcdef0123456789");
        assertThat(handle.length()).isEqualTo(34);
        assertThat(PublicHandleNormalizer.isValidHandle(handle)).isTrue();
    }

    @Test
    @DisplayName("Tên không hợp lệ vẫn sinh candidate hợp lệ qua neutral fallback và discriminator")
    void shouldGenerateValidCandidatesWhenDisplayNameIsInvalid() {
        String candidate0 = PublicHandleGenerator.candidateForAttempt("😊✨", TEST_USER_ID, 0);
        assertThat(candidate0).isEqualTo("user_abcdef012345");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate0)).isTrue();

        String candidate1 = PublicHandleGenerator.candidateForAttempt("😊✨", TEST_USER_ID, 1);
        assertThat(candidate1).isEqualTo("user_abcdef012345_abcd");
        assertThat(PublicHandleNormalizer.isValidHandle(candidate1)).isTrue();
    }
}
