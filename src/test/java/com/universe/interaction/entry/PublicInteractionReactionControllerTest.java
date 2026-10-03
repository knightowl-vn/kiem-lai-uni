package com.universe.interaction.entry;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityTestSupport;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.mutation.RemoveReactionCommand;
import com.universe.interaction.application.mutation.RemoveReactionUseCase;
import com.universe.interaction.application.mutation.SetReactionCommand;
import com.universe.interaction.application.mutation.SetReactionUseCase;
import com.universe.interaction.application.query.GetReactionSummaryUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicInteractionReactionController Standalone Tests")
class PublicInteractionReactionControllerTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private GetReactionSummaryUseCase getReactionSummaryUseCase;

    @Mock
    private SetReactionUseCase setReactionUseCase;

    @Mock
    private RemoveReactionUseCase removeReactionUseCase;

    private PublicInteractionReactionController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new PublicInteractionReactionController(
                getReactionSummaryUseCase,
                setReactionUseCase,
                removeReactionUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private RequestPostProcessor attachRequestIdentity(UUID userId) {
        return request -> {
            AuthenticatedRequestIdentityTestSupport.attach(
                    request,
                    new AuthenticatedRequestIdentity(
                            userId,
                            "user@universe.local",
                            "Active User",
                            null,
                            "active_user",
                            UserStatus.ACTIVE,
                            UserRole.USER
                    )
            );
            return request;
        };
    }

    private Map<ReactionType, Long> defaultCounts(long like, long love, long fire, long haha, long sad) {
        Map<ReactionType, Long> counts = new EnumMap<>(ReactionType.class);
        counts.put(ReactionType.LIKE, like);
        counts.put(ReactionType.LOVE, love);
        counts.put(ReactionType.FIRE, fire);
        counts.put(ReactionType.HAHA, haha);
        counts.put(ReactionType.SAD, sad);
        return counts;
    }

    @Nested
    @DisplayName("1. Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Throws NullPointerException when any dependency is null")
        void shouldThrowWhenDependenciesAreNull() {
            assertThatThrownBy(() -> new PublicInteractionReactionController(null, setReactionUseCase, removeReactionUseCase))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("GetReactionSummaryUseCase");

            assertThatThrownBy(() -> new PublicInteractionReactionController(getReactionSummaryUseCase, null, removeReactionUseCase))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("SetReactionUseCase");

            assertThatThrownBy(() -> new PublicInteractionReactionController(getReactionSummaryUseCase, setReactionUseCase, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("RemoveReactionUseCase");
        }
    }

    @Nested
    @DisplayName("2. GET /api/interaction/reactions")
    class GetReactionSummaryTests {

        @Test
        @DisplayName("Anonymous GET on eligible target -> 200 OK with summary and null currentUserReaction")
        void shouldReturn200ForAnonymousGet() throws Exception {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            ReactionSummary summary = ReactionSummary.of(
                    target,
                    defaultCounts(2, 3, 1, 0, 0),
                    null
            );

            when(getReactionSummaryUseCase.execute(eq(target), eq(null)))
                    .thenReturn(summary);

            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "NOVEL_CHAPTER")
                            .param("targetId", TARGET_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetType").value("NOVEL_CHAPTER"))
                    .andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
                    .andExpect(jsonPath("$.counts.LIKE").value(2))
                    .andExpect(jsonPath("$.counts.LOVE").value(3))
                    .andExpect(jsonPath("$.counts.FIRE").value(1))
                    .andExpect(jsonPath("$.counts.HAHA").value(0))
                    .andExpect(jsonPath("$.counts.SAD").value(0))
                    .andExpect(jsonPath("$.totalCount").value(6))
                    .andExpect(jsonPath("$.currentUserReaction").doesNotExist());
        }

        @Test
        @DisplayName("Authenticated GET on eligible target -> 200 OK with active currentUserReaction")
        void shouldReturn200ForAuthenticatedGet() throws Exception {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            ReactionSummary summary = ReactionSummary.of(
                    target,
                    defaultCounts(5, 10, 0, 5, 1),
                    ReactionType.LIKE
            );

            when(getReactionSummaryUseCase.execute(eq(target), eq(USER_ID)))
                    .thenReturn(summary);

            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "COMMENT")
                            .param("targetId", TARGET_ID.toString())
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetType").value("COMMENT"))
                    .andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
                    .andExpect(jsonPath("$.counts.LIKE").value(5))
                    .andExpect(jsonPath("$.counts.LOVE").value(10))
                    .andExpect(jsonPath("$.counts.FIRE").value(0))
                    .andExpect(jsonPath("$.counts.HAHA").value(5))
                    .andExpect(jsonPath("$.counts.SAD").value(1))
                    .andExpect(jsonPath("$.totalCount").value(21))
                    .andExpect(jsonPath("$.currentUserReaction").value("LIKE"));
        }

        @Test
        @DisplayName("GET on ineligible/missing target -> 404 Not Found")
        void shouldReturn404WhenTargetNotIneligible() throws Exception {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            when(getReactionSummaryUseCase.execute(eq(target), any()))
                    .thenThrow(new ReactionTargetNotEligibleException(target));

            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "NOVEL_CHAPTER")
                            .param("targetId", TARGET_ID.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET on DONGHUA_EPISODE target -> fails closed with 404 Not Found")
        void shouldReturn404WhenDonghuaEpisodeTargetIneligible() throws Exception {
            ReactionTarget target = ReactionTarget.donghuaEpisode(TARGET_ID);
            when(getReactionSummaryUseCase.execute(eq(target), any()))
                    .thenThrow(new ReactionTargetNotEligibleException(target));

            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "DONGHUA_EPISODE")
                            .param("targetId", TARGET_ID.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET with missing or invalid targetType -> 400 Bad Request")
        void shouldReturn400WhenTargetTypeIsInvalidOrMissing() throws Exception {
            // Missing targetType
            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetId", TARGET_ID.toString()))
                    .andExpect(status().isBadRequest());

            // Invalid targetType enum
            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "UNSUPPORTED_TYPE")
                            .param("targetId", TARGET_ID.toString()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET with missing or invalid targetId -> 400 Bad Request")
        void shouldReturn400WhenTargetIdIsInvalidOrMissing() throws Exception {
            // Missing targetId
            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "NOVEL_CHAPTER"))
                    .andExpect(status().isBadRequest());

            // Invalid targetId UUID format
            mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "NOVEL_CHAPTER")
                            .param("targetId", "not-a-valid-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Internal IllegalArgumentException from use case is not swallowed as HTTP 400")
        void shouldNotSwallowInternalIllegalArgumentExceptionAs400() {
            ReactionTarget target = ReactionTarget.novelChapter(TARGET_ID);
            when(getReactionSummaryUseCase.execute(eq(target), any()))
                    .thenThrow(new IllegalArgumentException("Internal invariant corrupted"));

            assertThatThrownBy(() -> mockMvc.perform(get("/api/interaction/reactions")
                            .param("targetType", "NOVEL_CHAPTER")
                            .param("targetId", TARGET_ID.toString())))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("3. PUT /api/interaction/reactions")
    class SetReactionTests {

        @Test
        @DisplayName("Authenticated PUT with reactionType LIKE -> sets reaction and returns 200 OK with fresh summary")
        void shouldSetReactionWhenAuthenticated() throws Exception {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            Reaction reaction = Reaction.create(
                    UUID.randomUUID(),
                    USER_ID,
                    target,
                    ReactionType.LIKE,
                    Instant.now()
            );

            when(setReactionUseCase.execute(any(SetReactionCommand.class))).thenReturn(reaction);

            ReactionSummary updatedSummary = ReactionSummary.of(
                    target,
                    defaultCounts(1, 0, 0, 0, 0),
                    ReactionType.LIKE
            );
            when(getReactionSummaryUseCase.execute(eq(target), eq(USER_ID))).thenReturn(updatedSummary);

            String requestJson = """
                    {
                        "targetType": "COMMENT",
                        "targetId": "%s",
                        "reactionType": "LIKE"
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetType").value("COMMENT"))
                    .andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
                    .andExpect(jsonPath("$.counts.LIKE").value(1))
                    .andExpect(jsonPath("$.totalCount").value(1))
                    .andExpect(jsonPath("$.currentUserReaction").value("LIKE"));

            ArgumentCaptor<SetReactionCommand> captor = ArgumentCaptor.forClass(SetReactionCommand.class);
            verify(setReactionUseCase).execute(captor.capture());
            assertThat(captor.getValue().target()).isEqualTo(target);
            assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
            assertThat(captor.getValue().reactionType()).isEqualTo(ReactionType.LIKE);
            verify(removeReactionUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("Authenticated PUT with null/empty reactionType -> removes reaction and returns 200 OK with fresh summary")
        void shouldRemoveReactionWhenReactionTypeIsNull() throws Exception {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);

            when(removeReactionUseCase.execute(any(RemoveReactionCommand.class))).thenReturn(true);

            ReactionSummary updatedSummary = ReactionSummary.of(
                    target,
                    defaultCounts(0, 0, 0, 0, 0),
                    null
            );
            when(getReactionSummaryUseCase.execute(eq(target), eq(USER_ID))).thenReturn(updatedSummary);

            String requestJson = """
                    {
                        "targetType": "COMMENT",
                        "targetId": "%s",
                        "reactionType": null
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetType").value("COMMENT"))
                    .andExpect(jsonPath("$.targetId").value(TARGET_ID.toString()))
                    .andExpect(jsonPath("$.totalCount").value(0))
                    .andExpect(jsonPath("$.currentUserReaction").doesNotExist());

            ArgumentCaptor<RemoveReactionCommand> captor = ArgumentCaptor.forClass(RemoveReactionCommand.class);
            verify(removeReactionUseCase).execute(captor.capture());
            assertThat(captor.getValue().target()).isEqualTo(target);
            assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
            verify(setReactionUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("PUT without authenticated identity -> 401 Unauthorized")
        void shouldReturn401WhenAnonymous() throws Exception {
            String requestJson = """
                    {
                        "targetType": "COMMENT",
                        "targetId": "%s",
                        "reactionType": "LIKE"
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isUnauthorized());

            verify(setReactionUseCase, never()).execute(any());
            verify(removeReactionUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("PUT on ineligible target -> 404 Not Found")
        void shouldReturn404WhenTargetNotIneligible() throws Exception {
            ReactionTarget target = ReactionTarget.comment(TARGET_ID);
            when(setReactionUseCase.execute(any(SetReactionCommand.class)))
                    .thenThrow(new ReactionTargetNotEligibleException(target));

            String requestJson = """
                    {
                        "targetType": "COMMENT",
                        "targetId": "%s",
                        "reactionType": "LIKE"
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PUT on DONGHUA_EPISODE target -> fails closed with 404 Not Found")
        void shouldReturn404WhenDonghuaEpisodeTargetIneligibleOnPut() throws Exception {
            ReactionTarget target = ReactionTarget.donghuaEpisode(TARGET_ID);
            when(setReactionUseCase.execute(any(SetReactionCommand.class)))
                    .thenThrow(new ReactionTargetNotEligibleException(target));

            String requestJson = """
                    {
                        "targetType": "DONGHUA_EPISODE",
                        "targetId": "%s",
                        "reactionType": "LIKE"
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PUT with invalid reactionType string -> 400 Bad Request")
        void shouldReturn400WhenReactionTypeIsInvalid() throws Exception {
            String requestJson = """
                    {
                        "targetType": "COMMENT",
                        "targetId": "%s",
                        "reactionType": "INVALID_EMOJI"
                    }
                    """.formatted(TARGET_ID);

            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());

            verify(setReactionUseCase, never()).execute(any());
        }

        @Test
        @DisplayName("PUT with missing body, null targetType or null targetId -> 400 Bad Request")
        void shouldReturn400WhenBodyIsMalformed() throws Exception {
            // Null body
            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());

            // Missing targetId
            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetType\": \"COMMENT\"}")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());

            // Missing targetType
            mockMvc.perform(put("/api/interaction/reactions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetId\": \"" + TARGET_ID + "\"}")
                            .with(attachRequestIdentity(USER_ID)))
                    .andExpect(status().isBadRequest());
        }
    }
}
