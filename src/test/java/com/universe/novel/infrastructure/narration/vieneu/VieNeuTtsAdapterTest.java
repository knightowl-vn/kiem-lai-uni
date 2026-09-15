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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.universe.novel.infrastructure.narration.concurrency.NarrationTtsExecutionGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
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
    private NarrationTtsExecutionGate gate;
    private VieNeuTtsAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder realBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(realBuilder).build();
        restClientBuilder = Mockito.spy(realBuilder);
        Mockito.doReturn(restClientBuilder).when(restClientBuilder).requestFactory(any(ClientHttpRequestFactory.class));
        gate = new NarrationTtsExecutionGate(1);
        adapter = new VieNeuTtsAdapter(BASE_URL, restClientBuilder, gate);
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
        VieNeuTtsAdapter customAdapter = new VieNeuTtsAdapter("http://custom-host:8000///", customBuilder, gate);

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

        new VieNeuTtsAdapter("http://localhost:9000", connectTimeout, readTimeout, spyBuilder, gate);

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

    @Test
    @DisplayName("Local/direct mode GET /api/voices sends neither Cloudflare header")
    void localDirectModeGetVoicesSendsNeitherCloudflareHeader() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = adapter.listVoices();
        assertThat(voices).isEmpty();

        mockServer.verify();
    }

    @Test
    @DisplayName("Local/direct mode POST /api/tts/generate sends neither Cloudflare header")
    void localDirectModePostGenerateSendsNeitherCloudflareHeader() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER))
                .andRespond(withSuccess(new byte[]{82, 73, 70, 70}, MediaType.valueOf("audio/wav")));

        TtsSynthesisResult result = adapter.synthesize(new TtsSynthesisCommand("Test text", "voice-1"));
        assertThat(result.audioBytes()).isEqualTo(new byte[]{82, 73, 70, 70});

        mockServer.verify();
    }

    @Test
    @DisplayName("Authenticated GET sends exact configured Client ID and Client Secret headers")
    void authenticatedGetSendsConfiguredCloudflareHeaders() {
        RestClient.Builder authBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer authServer = MockRestServiceServer.bindTo(authBuilder).build();
        Mockito.doReturn(authBuilder).when(authBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter authAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "cf-client-id-123",
                "cf-client-secret-456",
                authBuilder,
                gate
        );

        authServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER, "cf-client-id-123"))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER, "cf-client-secret-456"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = authAdapter.listVoices();
        assertThat(voices).isEmpty();

        authServer.verify();
    }

    @Test
    @DisplayName("Authenticated POST sends both headers, unchanged text/voice_id payload, and returns audio/wav")
    void authenticatedPostSendsHeadersAndUnchangedPayloadAndReturnsAudioWav() {
        RestClient.Builder authBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer authServer = MockRestServiceServer.bindTo(authBuilder).build();
        Mockito.doReturn(authBuilder).when(authBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter authAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "cf-client-id-123",
                "cf-client-secret-456",
                authBuilder,
                gate
        );

        byte[] expectedAudio = new byte[]{82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86, 69};

        authServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER, "cf-client-id-123"))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER, "cf-client-secret-456"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.text").value("Trần Bình An cất bước ra đi."))
                .andExpect(jsonPath("$.voice_id").value("minh-duc"))
                .andRespond(withSuccess(expectedAudio, MediaType.valueOf("audio/wav")));

        TtsSynthesisResult result = authAdapter.synthesize(
                new TtsSynthesisCommand("Trần Bình An cất bước ra đi.", "minh-duc")
        );

        assertThat(result.audioBytes()).isEqualTo(expectedAudio);
        assertThat(result.mediaType()).isEqualTo("audio/wav");

        authServer.verify();
    }

    @Test
    @DisplayName("Partial credentials fail during construction and exception never contains credential values")
    void partialCredentialsFailWithoutLeakingValues() {
        String sensitiveId = "SUPER_SECRET_ID_999";
        String sensitiveSecret = "SUPER_SECRET_KEY_888";

        assertThatThrownBy(() -> new VieNeuTtsAdapter(BASE_URL, sensitiveId, null, restClientBuilder, gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both narration.tts.vieneu.cf-access-client-id and narration.tts.vieneu.cf-access-client-secret must be configured together")
                .hasMessageNotContaining(sensitiveId);

        assertThatThrownBy(() -> new VieNeuTtsAdapter(BASE_URL, sensitiveId, "   ", restClientBuilder, gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both narration.tts.vieneu.cf-access-client-id and narration.tts.vieneu.cf-access-client-secret must be configured together")
                .hasMessageNotContaining(sensitiveId);

        assertThatThrownBy(() -> new VieNeuTtsAdapter(BASE_URL, null, sensitiveSecret, restClientBuilder, gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both narration.tts.vieneu.cf-access-client-id and narration.tts.vieneu.cf-access-client-secret must be configured together")
                .hasMessageNotContaining(sensitiveSecret);

        assertThatThrownBy(() -> new VieNeuTtsAdapter(BASE_URL, "   ", sensitiveSecret, restClientBuilder, gate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both narration.tts.vieneu.cf-access-client-id and narration.tts.vieneu.cf-access-client-secret must be configured together")
                .hasMessageNotContaining(sensitiveSecret);
    }

    @Test
    @DisplayName("Whitespace-only values count as absent and operate in local/direct mode")
    void whitespaceOnlyValuesCountAsAbsent() {
        RestClient.Builder wsBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer wsServer = MockRestServiceServer.bindTo(wsBuilder).build();
        Mockito.doReturn(wsBuilder).when(wsBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter wsAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "   ",
                "   ",
                wsBuilder,
                gate
        );

        wsServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = wsAdapter.listVoices();
        assertThat(voices).isEmpty();

        wsServer.verify();
    }

    @Test
    @DisplayName("Surrounding whitespace on configured credential values is stripped")
    void surroundingWhitespaceOnCredentialsIsStripped() {
        RestClient.Builder trimBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer trimServer = MockRestServiceServer.bindTo(trimBuilder).build();
        Mockito.doReturn(trimBuilder).when(trimBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter trimAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "  padded-client-id  ",
                "  padded-client-secret  ",
                trimBuilder,
                gate
        );

        trimServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER, "padded-client-id"))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER, "padded-client-secret"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = trimAdapter.listVoices();
        assertThat(voices).isEmpty();

        trimServer.verify();
    }

    @Test
    @DisplayName("Unicode whitespace is stripped from credentials and unicode whitespace-only counts as absent")
    void unicodeWhitespaceIsStrippedAndUnicodeWhitespaceOnlyCountsAsAbsent() {
        RestClient.Builder unicodeBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer unicodeServer = MockRestServiceServer.bindTo(unicodeBuilder).build();
        Mockito.doReturn(unicodeBuilder).when(unicodeBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter unicodeAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "\u2003\t padded-client-id \u2003",
                "\u2003  padded-client-secret \u2003\n",
                unicodeBuilder,
                gate
        );

        unicodeServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER, "padded-client-id"))
                .andExpect(header(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER, "padded-client-secret"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> voices = unicodeAdapter.listVoices();
        assertThat(voices).isEmpty();

        unicodeServer.verify();

        RestClient.Builder wsBuilder = Mockito.spy(RestClient.builder());
        MockRestServiceServer wsServer = MockRestServiceServer.bindTo(wsBuilder).build();
        Mockito.doReturn(wsBuilder).when(wsBuilder).requestFactory(any(ClientHttpRequestFactory.class));

        VieNeuTtsAdapter wsAdapter = new VieNeuTtsAdapter(
                BASE_URL,
                "\u2003\u2003",
                "\u2003 \t \u2003",
                wsBuilder,
                gate
        );

        wsServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_ID_HEADER))
                .andExpect(headerDoesNotExist(VieNeuTtsAdapter.CF_ACCESS_CLIENT_SECRET_HEADER))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<TtsProviderVoice> wsVoices = wsAdapter.listVoices();
        assertThat(wsVoices).isEmpty();

        wsServer.verify();
    }

    @Test
    @DisplayName("synthesize() executes through NarrationTtsExecutionGate and releases permit after success")
    void synthesizeExecutesThroughGateAndReleasesPermitOnSuccess() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.valueOf("audio/wav")));

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        TtsSynthesisResult result = adapter.synthesize(new TtsSynthesisCommand("test", "voice-1"));

        assertThat(result.audioBytes()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(gate.getAvailablePermits()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("synthesize() releases permit if provider call throws TtsProviderException")
    void synthesizeReleasesPermitOnProviderException() {
        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThat(gate.getAvailablePermits()).isEqualTo(1);

        assertThatThrownBy(() -> adapter.synthesize(new TtsSynthesisCommand("test", "voice-1")))
                .isInstanceOf(TtsProviderException.class);

        assertThat(gate.getAvailablePermits()).isEqualTo(1);
        mockServer.verify();
    }

    @Test
    @DisplayName("Two concurrent synthesize() calls with capacity 1 never execute provider work simultaneously")
    void concurrentSynthesizeCallsAreSerializedByGate() throws Exception {
        AtomicInteger activeCalls = new AtomicInteger(0);
        AtomicInteger maxConcurrentCalls = new AtomicInteger(0);
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    int active = activeCalls.incrementAndGet();
                    maxConcurrentCalls.accumulateAndGet(active, Math::max);
                    firstCallStarted.countDown();
                    try {
                        releaseFirstCall.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    activeCalls.decrementAndGet();
                    return withSuccess(new byte[]{1, 2, 3}, MediaType.valueOf("audio/wav")).createResponse(request);
                });

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    int active = activeCalls.incrementAndGet();
                    maxConcurrentCalls.accumulateAndGet(active, Math::max);
                    activeCalls.decrementAndGet();
                    return withSuccess(new byte[]{4, 5, 6}, MediaType.valueOf("audio/wav")).createResponse(request);
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TtsSynthesisResult> future1 = executor.submit(() ->
                    adapter.synthesize(new TtsSynthesisCommand("first", "voice-1")));

            assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.getAvailablePermits()).isEqualTo(0);

            Future<TtsSynthesisResult> future2 = executor.submit(() ->
                    adapter.synthesize(new TtsSynthesisCommand("second", "voice-1")));

            Thread.sleep(100);
            assertThat(gate.getQueueLength()).isEqualTo(1);
            assertThat(maxConcurrentCalls.get()).isEqualTo(1);

            releaseFirstCall.countDown();

            TtsSynthesisResult result1 = future1.get(5, TimeUnit.SECONDS);
            TtsSynthesisResult result2 = future2.get(5, TimeUnit.SECONDS);

            assertThat(result1.audioBytes()).isEqualTo(new byte[]{1, 2, 3});
            assertThat(result2.audioBytes()).isEqualTo(new byte[]{4, 5, 6});
            assertThat(maxConcurrentCalls.get()).isEqualTo(1);
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
            mockServer.verify();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("listVoices() is NOT serialized by the synthesis gate while synthesis is holding the permit")
    void listVoicesIsNotSerializedBySynthesisGate() throws Exception {
        CountDownLatch synthesisStarted = new CountDownLatch(1);
        CountDownLatch releaseSynthesis = new CountDownLatch(1);

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/tts/generate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    synthesisStarted.countDown();
                    try {
                        releaseSynthesis.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(new byte[]{1, 2, 3}, MediaType.valueOf("audio/wav")).createResponse(request);
                });

        mockServer.expect(requestTo("http://mock-vieneu:9000/api/voices"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TtsSynthesisResult> synthesisFuture = executor.submit(() ->
                    adapter.synthesize(new TtsSynthesisCommand("test", "voice-1")));

            assertThat(synthesisStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.getAvailablePermits()).isEqualTo(0);

            List<TtsProviderVoice> voices = adapter.listVoices();
            assertThat(voices).isEmpty();

            releaseSynthesis.countDown();
            TtsSynthesisResult result = synthesisFuture.get(5, TimeUnit.SECONDS);
            assertThat(result.audioBytes()).isEqualTo(new byte[]{1, 2, 3});
            assertThat(gate.getAvailablePermits()).isEqualTo(1);
            mockServer.verify();
        } finally {
            executor.shutdownNow();
        }
    }
}
