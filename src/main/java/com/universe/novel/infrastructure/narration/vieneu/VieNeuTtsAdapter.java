package com.universe.novel.infrastructure.narration.vieneu;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.universe.novel.application.exceptions.TtsProviderException;
import com.universe.novel.application.narration.TtsProviderVoice;
import com.universe.novel.application.narration.TtsSynthesisCommand;
import com.universe.novel.application.narration.TtsSynthesisResult;
import com.universe.novel.application.ports.TtsProviderPort;
import com.universe.novel.infrastructure.narration.concurrency.NarrationTtsExecutionGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * VieNeu HTTP implementation of {@link TtsProviderPort}.
 * <p>
 * Communicates with an external VieNeu TTS server via REST endpoints:
 * <ul>
 *     <li>{@code GET /api/voices} - retrieves provider voice catalog</li>
 *     <li>{@code POST /api/tts/generate} - synthesizes Vietnamese narration text into WAV audio</li>
 * </ul>
 */
@Component
public class VieNeuTtsAdapter implements TtsProviderPort {

    public static final String CF_ACCESS_CLIENT_ID_HEADER = "CF-Access-Client-Id";
    public static final String CF_ACCESS_CLIENT_SECRET_HEADER = "CF-Access-Client-Secret";

    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final RestClient restClient;
    private final NarrationTtsExecutionGate ttsExecutionGate;

    @Autowired
    public VieNeuTtsAdapter(
            @Value("${narration.tts.vieneu.base-url:http://localhost:8000}") String baseUrl,
            @Value("${narration.tts.vieneu.connect-timeout:10s}") Duration connectTimeout,
            @Value("${narration.tts.vieneu.read-timeout:120s}") Duration readTimeout,
            @Value("${narration.tts.vieneu.cf-access-client-id:}") String cfAccessClientId,
            @Value("${narration.tts.vieneu.cf-access-client-secret:}") String cfAccessClientSecret,
            @Autowired(required = false) RestClient.Builder restClientBuilder,
            NarrationTtsExecutionGate ttsExecutionGate
    ) {
        this.ttsExecutionGate = Objects.requireNonNull(ttsExecutionGate, "ttsExecutionGate must not be null");
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10));
        requestFactory.setReadTimeout(readTimeout != null ? readTimeout : Duration.ofSeconds(120));
        builder.requestFactory(requestFactory);

        String cleanClientId = cleanCredential(cfAccessClientId);
        String cleanClientSecret = cleanCredential(cfAccessClientSecret);

        boolean hasClientId = cleanClientId != null;
        boolean hasClientSecret = cleanClientSecret != null;

        if (hasClientId ^ hasClientSecret) {
            throw new IllegalStateException(
                    "Cloudflare Access configuration is partial: both narration.tts.vieneu.cf-access-client-id "
                            + "and narration.tts.vieneu.cf-access-client-secret must be configured together."
            );
        }

        if (hasClientId && hasClientSecret) {
            builder.defaultHeader(CF_ACCESS_CLIENT_ID_HEADER, cleanClientId);
            builder.defaultHeader(CF_ACCESS_CLIENT_SECRET_HEADER, cleanClientSecret);
        }

        this.restClient = builder.baseUrl(normalizedUrl).build();
    }

    public VieNeuTtsAdapter(String baseUrl, RestClient.Builder restClientBuilder, NarrationTtsExecutionGate ttsExecutionGate) {
        this(baseUrl, Duration.ofSeconds(10), Duration.ofSeconds(120), null, null, restClientBuilder, ttsExecutionGate);
    }

    public VieNeuTtsAdapter(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            RestClient.Builder restClientBuilder,
            NarrationTtsExecutionGate ttsExecutionGate
    ) {
        this(baseUrl, connectTimeout, readTimeout, null, null, restClientBuilder, ttsExecutionGate);
    }

    public VieNeuTtsAdapter(
            String baseUrl,
            String cfAccessClientId,
            String cfAccessClientSecret,
            RestClient.Builder restClientBuilder,
            NarrationTtsExecutionGate ttsExecutionGate
    ) {
        this(baseUrl, Duration.ofSeconds(10), Duration.ofSeconds(120), cfAccessClientId, cfAccessClientSecret, restClientBuilder, ttsExecutionGate);
    }

    private static String cleanCredential(String credential) {
        if (credential == null) {
            return null;
        }
        String stripped = credential.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    @Override
    public List<TtsProviderVoice> listVoices() {
        try {
            VieNeuVoiceResponse[] voices = restClient.get()
                    .uri("/api/voices")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String errorBody = extractBodySafely(response);
                        throw new TtsProviderException(
                                "VieNeu list voices failed with status " + response.getStatusCode() + ": " + errorBody
                        );
                    })
                    .body(VieNeuVoiceResponse[].class);

            if (voices == null || voices.length == 0) {
                return List.of();
            }

            return Arrays.stream(voices)
                    .filter(v -> v != null && v.voiceId() != null && !v.voiceId().isBlank())
                    .map(v -> new TtsProviderVoice(
                            v.voiceId().trim(),
                            v.label() != null ? v.label().trim() : null
                    ))
                    .toList();
        } catch (TtsProviderException e) {
            throw e;
        } catch (RestClientException e) {
            throw new TtsProviderException("Failed to communicate with VieNeu TTS service: " + e.getMessage(), e);
        }
    }

    @Override
    public TtsSynthesisResult synthesize(TtsSynthesisCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Synthesis command must not be null");
        }
        if (command.text() == null || command.text().isBlank()) {
            throw new IllegalArgumentException("Synthesis text must not be null or blank");
        }
        if (command.providerVoiceId() == null || command.providerVoiceId().isBlank()) {
            throw new IllegalArgumentException("Provider voice ID must not be null or blank");
        }

        return ttsExecutionGate.execute(() -> performSynthesis(command));
    }

    private TtsSynthesisResult performSynthesis(TtsSynthesisCommand command) {
        try {
            VieNeuGenerateRequest requestPayload = new VieNeuGenerateRequest(
                    command.text(),
                    command.providerVoiceId()
            );

            ResponseEntity<byte[]> response = restClient.post()
                    .uri("/api/tts/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("audio/wav"), MediaType.valueOf("audio/*"))
                    .body(requestPayload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, responseStatus) -> {
                        String errorBody = extractBodySafely(responseStatus);
                        throw new TtsProviderException(
                                "VieNeu TTS generation failed with status " + responseStatus.getStatusCode() + ": " + errorBody
                        );
                    })
                    .toEntity(byte[].class);

            byte[] audioBytes = response.getBody();
            if (audioBytes == null || audioBytes.length == 0) {
                throw new TtsProviderException("VieNeu TTS returned empty audio payload");
            }

            MediaType mediaType = response.getHeaders().getContentType();
            if (mediaType == null || !"audio".equalsIgnoreCase(mediaType.getType())) {
                throw new TtsProviderException(
                        "VieNeu TTS returned invalid or non-audio media type: " + mediaType
                );
            }

            return new TtsSynthesisResult(audioBytes, mediaType.toString());
        } catch (TtsProviderException e) {
            throw e;
        } catch (RestClientException e) {
            throw new TtsProviderException("Failed to synthesize audio from VieNeu TTS service: " + e.getMessage(), e);
        }
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("VieNeu base URL must not be null or blank");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String extractBodySafely(ClientHttpResponse response) {
        try {
            byte[] bodyBytes = response.getBody().readAllBytes();
            if (bodyBytes.length == 0) {
                return "<empty body>";
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8).trim();
            if (body.length() > MAX_ERROR_BODY_LENGTH) {
                return body.substring(0, MAX_ERROR_BODY_LENGTH) + "... (truncated)";
            }
            return body.isEmpty() ? "<empty body>" : body;
        } catch (Exception ignored) {
            return "<unable to read body>";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VieNeuVoiceResponse(
            @JsonProperty("voiceId")
            @JsonAlias("voice_id")
            String voiceId,

            @JsonProperty("label")
            String label
    ) {
    }

    record VieNeuGenerateRequest(
            @JsonProperty("text")
            String text,

            @JsonProperty("voice_id")
            String voiceId
    ) {
    }
}
