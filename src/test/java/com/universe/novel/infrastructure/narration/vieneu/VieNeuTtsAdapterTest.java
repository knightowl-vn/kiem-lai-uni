package com.universe.novel.infrastructure.narration.vieneu;

import com.universe.novel.application.exceptions.TtsProviderException;
import com.universe.novel.application.narration.TtsProviderVoice;
import com.universe.novel.application.narration.TtsSynthesisCommand;
import com.universe.novel.application.narration.TtsSynthesisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VieNeuTtsAdapterTest {

    private static final String BASE_URL = "http://mock-vieneu:9000";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private VieNeuTtsAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder realBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(realBuilder).build();
        restClientBuilder = Mockito.spy(realBuilder);
        Mockito.doReturn(restClientBuilder).when(restClientBuilder).requestFactory(any(ClientHttpRequestFactory.class));
        adapter = new VieNeuTtsAdapter(BASE_URL, restClientBuilder);
    }

    @Test
    @DisplayName("1. Actual voice-list mapping: Maps voiceId and label from provider JSON response")
    void shouldMapProviderVoicesAccurately() {
        String jsonResponse = """
                [
                    {"voiceId": "minh-duc", "label": "Minh Đức - Nam Bắc"},
                    {"voice_id": "pham-tuyen", "label": "Phạm Tuyên - Nam Bắc"}
                ]
                """;

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = adapter.listVoices();

        assertThat(voices).hasSize(2);
        assertThat(voices.get(0).voiceId()).isEqualTo("minh-duc");
        assertThat(voices.get(0).label()).isEqualTo("Minh Đức - Nam Bắc");
        assertThat(voices.get(1).voiceId()).isEqualTo("pham-tuyen");
        assertThat(voices.get(1).label()).isEqualTo("Phạm Tuyên - Nam Bắc");

        mockServer.verify();
    }

    @Test
    @DisplayName("2. Successful audio/wav synthesis: Returns audioBytes and mediaType from valid WAV response")
    void shouldSynthesizeWavAudioSuccessfully() {
        byte[] expectedAudioBytes = new byte[]{82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86, 69}; // RIFF...WAVE

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("Trần Bình An cất bước ra đi."))
                .andExpect(jsonPath("$.voice_id").value("minh-duc"))
                .andRespond(withSuccess(expectedAudioBytes, MediaType.valueOf("audio/wav")));

        TtsSynthesisResult result = adapter.synthesize(new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc"));

        assertThat(result.audioBytes()).isEqualTo(expectedAudioBytes);
        assertThat(result.mediaType()).isEqualTo("audio/wav");

        mockServer.verify();
    }

    @Test
    @DisplayName("3. providerVoiceId serialized as voice_id in request payload")
    void shouldSerializeProviderVoiceIdAsVoiceIdField() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.voice_id").value("custom-voice-999"))
                .andExpect(jsonPath("$.text").value("Kiếm Lai"))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.valueOf("audio/wav")));

        adapter.synthesize(new TtsSynthesisCommand("Kiếm Lai", "custom-voice-999"));

        mockServer.verify();
    }

    @Test
    @DisplayName("4. Empty audio response is rejected with TtsProviderException")
    void shouldRejectEmptyAudioResponse() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(new byte[0], MediaType.valueOf("audio/wav")));

        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Kiếm Lai", "voice-1")))
                .isInstanceOf(TtsProviderException.class)
                .hasMessageContaining("empty audio payload");

        mockServer.verify();
    }

    @Test
    @DisplayName("5. HTTP 200 non-audio response is rejected with TtsProviderException")
    void shouldRejectNonAudioMediaType() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Kiếm Lai", "voice-1")))
                .isInstanceOf(TtsProviderException.class)
                .hasMessageContaining("invalid or non-audio media type");

        mockServer.verify();
    }

    @Test
    @DisplayName("6. Non-2xx response maps to TtsProviderException with bounded error message")
    void shouldMapNon2xxResponseToTtsProviderException() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body("CUDA Out Of Memory"));

        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Đại đạo triều thiên", "voice-1")))
                .isInstanceOf(TtsProviderException.class)
                .hasMessageContaining("VieNeu TTS generation failed with status 500")
                .hasMessageContaining("CUDA Out Of Memory");

        mockServer.verify();
    }

    @Test
    @DisplayName("6b. Non-2xx on voice listing maps to TtsProviderException")
    void shouldMapNon2xxOnVoiceListingToTtsProviderException() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withBadRequest().body("Invalid query parameter"));

        assertThatThrownBy(() -> adapter.listVoices())
                .isInstanceOf(TtsProviderException.class)
                .hasMessageContaining("VieNeu list voices failed with status 400");

        mockServer.verify();
    }

    @Test
    @DisplayName("7. Transport failure maps to TtsProviderException without leaking RestClientException")
    void shouldMapTransportFailureToTtsProviderExceptionWithoutLeakingRawExceptions() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("Connection timed out after 5000ms")));

        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Đạo khả đạo, phi thường đạo", "voice-1")))
                .isInstanceOf(TtsProviderException.class)
                .hasMessageContaining("Failed to synthesize audio from VieNeu TTS service")
                .hasCauseInstanceOf(Exception.class);

        mockServer.verify();
    }

    @Test
    @DisplayName("8. Configured base URL is used and normalized without trailing slashes")
    void shouldNormalizeAndUseConfiguredBaseUrl() {
        RestClient.Builder realBuilder = RestClient.builder();
        MockRestServiceServer customServer = MockRestServiceServer.bindTo(realBuilder).build();
        RestClient.Builder customBuilder = Mockito.spy(realBuilder);
        Mockito.doReturn(customBuilder).when(customBuilder).requestFactory(any(ClientHttpRequestFactory.class));
        VieNeuTtsAdapter customAdapter = new VieNeuTtsAdapter("http://custom-host:8000///", customBuilder);

        customServer.expect(requestTo("http://custom-host:8000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = customAdapter.listVoices();
        assertThat(voices).isEmpty();

        customServer.verify();
    }

    @Test
    @DisplayName("9. Injected RestClient.Builder receives configured request factory with connect and read timeouts")
    void shouldApplyConfiguredTimeoutsToInjectedBuilder() throws Exception {
        RestClient.Builder spyBuilder = Mockito.spy(RestClient.builder());
        Mockito.doReturn(spyBuilder).when(spyBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        Duration connectTimeout = Duration.ofSeconds(15);
        Duration readTimeout = Duration.ofSeconds(90);

        new VieNeuTtsAdapter("http://localhost:9000", connectTimeout, readTimeout, spyBuilder);

        ArgumentCaptor<ClientHttpRequestFactory> captor = ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(spyBuilder).requestFactory(captor.capture());

        ClientHttpRequestFactory factory = captor.getValue();
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);

        Field connectField = SimpleClientHttpRequestFactory.class.getDeclaredField("connectTimeout");
        connectField.setAccessible(true);
        Object connectVal = connectField.get(factory);

        Field readField = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readField.setAccessible(true);
        Object readVal = readField.get(factory);

        if (connectVal instanceof Duration d) {
            assertThat(d).isEqualTo(connectTimeout);
        } else if (connectVal instanceof Number n) {
            assertThat(n.longValue()).isEqualTo(connectTimeout.toMillis());
        }

        if (readVal instanceof Duration d) {
            assertThat(d).isEqualTo(readTimeout);
        } else if (readVal instanceof Number n) {
            assertThat(n.longValue()).isEqualTo(readTimeout.toMillis());
        }
    }

    @Test
    @DisplayName("Validates input command and required fields")
    void shouldValidateInputCommand() {
        assertThatThrownBy(() -> adapter.synthesize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand(null, "voice-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("   ", "voice-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Valid text", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("Valid text", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
