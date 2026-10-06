package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.AiAnalysisResponse.Media;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiClientTest {
	private static final String MODEL = "gemini-3.1-flash-lite";
	private static final String IMAGE = "data:image/jpeg;base64,AQ==";
	private final HttpClient http = mock(HttpClient.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final GeminiClient client = new GeminiClient(http, mapper, true, "test-only-key", MODEL, 90);
	private final List<Media> media = List.of(new Media(11, 1, "image/png"));
	private final Logger logger = (Logger) LoggerFactory.getLogger(GeminiClient.class);
	private final ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
	private Level previousLevel;
	private boolean previousAdditive;
	private static final String RESULT = """
			{"summary":"摘要","pages":[{"index":1,"description":"无法读清对白","texts":[]}],
			"characters":[{"name":"黑发角色","description":"身份不确定"}],"relationships":[],"suggestedTags":["漫画"]}
			""";

	@BeforeEach
	void captureLogs() {
		previousLevel = logger.getLevel();
		previousAdditive = logger.isAdditive();
		logger.setLevel(Level.WARN);
		logger.setAdditive(false);
		capturedLogs.start();
		logger.addAppender(capturedLogs);
	}

	@AfterEach
	void detachLogs() {
		logger.detachAppender(capturedLogs);
		capturedLogs.stop();
		logger.setLevel(previousLevel);
		logger.setAdditive(previousAdditive);
	}

	@SuppressWarnings("unchecked")
	private void reply(int status, String body) {
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(CompletableFuture.completedFuture(response));
	}

	private String envelope(String result) {
		return mapper.writeValueAsString(java.util.Map.of("modelVersion", MODEL + "-version", "candidates", List.of(
				java.util.Map.of("finishReason", "STOP", "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", result)))))));
	}

	@Test
	void requestUsesGeminiRestOrderedInlineImagesJsonSchemaAndBackendKey() {
		var twoPages = (tools.jackson.databind.node.ObjectNode) mapper.readTree(RESULT);
		twoPages.set("pages", mapper.valueToTree(List.of(java.util.Map.of("index", 1, "description", "图1", "texts", List.of()),
				java.util.Map.of("index", 2, "description", "图2", "texts", List.of()))));
		reply(200, envelope(twoPages.toString()));
		var skipped = List.of(new Media(12, 2, "video/mp4"));
		var inputs = List.of(media.get(0), new Media(13, 3, "image/png"));
		var result = client.analyze(List.of(IMAGE, "data:image/jpeg;base64,Ag=="), inputs, skipped);
		assertEquals("摘要", result.summary());
		assertEquals("黑发角色", result.characters().get(0).name());
		assertEquals(inputs, result.analyzedAssets());
		assertEquals(skipped, result.skippedAssets());
		assertEquals(MODEL + "-version", result.model());
		verify(http).sendAsync(argThat(request -> {
			assertEquals("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent", request.uri().toString());
			assertEquals("POST", request.method());
			assertEquals("test-only-key", request.headers().firstValue("x-goog-api-key").orElseThrow());
			assertNull(request.uri().getQuery());
			assertTrue(request.headers().firstValue("Authorization").isEmpty());
			assertEquals(90, request.timeout().orElseThrow().toSeconds());
			var json = mapper.readTree(body(request));
			assertEquals(3, json.size());
			var generationConfig = json.path("generationConfig");
			assertEquals(3000, generationConfig.path("maxOutputTokens").asInt());
			assertFalse(generationConfig.has("responseFormat"), "Do not send the rejected responseFormat.text.mimeType path");
			assertEquals("application/json", generationConfig.path("responseMimeType").asText());
			var schema = generationConfig.path("responseSchema");
			assertEquals("object", schema.path("type").asText());
			assertEquals(mapper.valueToTree(List.of("summary", "pages", "characters", "relationships", "suggestedTags")), schema.path("required"));
			assertEquals(5, schema.path("properties").size());
			assertTrue(schema.findValues("additionalProperties").isEmpty(), "No additionalProperties at any schema depth");
			var pageSchema = schema.path("properties").path("pages").path("items");
			assertEquals(mapper.valueToTree(List.of("index", "description", "texts")), pageSchema.path("required"));
			assertEquals("array", pageSchema.path("properties").path("texts").path("type").asText());
			var textSchema = pageSchema.path("properties").path("texts").path("items");
			assertEquals(mapper.valueToTree(List.of("source", "translation", "note")), textSchema.path("required"));
			assertEquals(3, textSchema.path("properties").size());
			for (String field : List.of("source", "translation", "note")) {
				assertEquals("string", textSchema.path("properties").path(field).path("type").asText());
			}
			assertEquals(mapper.valueToTree(List.of("name", "description")), schema.path("properties").path("characters").path("items").path("required"));
			var content = json.path("contents").path(0).path("parts");
			assertEquals("user", json.path("contents").path(0).path("role").asText());
			assertTrue(content.path(0).path("text").isString());
			assertEquals("image/jpeg", content.path(1).path("inlineData").path("mimeType").asText());
			assertEquals("AQ==", content.path(1).path("inlineData").path("data").asText());
			assertEquals("Ag==", content.path(2).path("inlineData").path("data").asText());
			String prompt = json.path("systemInstruction").path("parts").path(0).path("text").asText();
			for (String instruction : List.of("禁止依据作品常识猜具体姓名", "大致阅读顺序", "对话框、旁白、注释、拟声词",
					"source 尽量保留图片中的原文", "翻译成简体中文", "[无法辨认]", "禁止凭剧情脑补", "texts 返回空数组",
					"用户名、水印、作者签名")) assertTrue(prompt.contains(instruction), instruction);
			return true;
		}), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void acceptsFourPagesWithConsecutiveIndices() {
		var pages = java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(i -> java.util.Map.of("index", i, "description", "图" + i, "texts", List.of())).toList();
		var json = mapper.readTree(RESULT).deepCopy();
		((tools.jackson.databind.node.ObjectNode) json).set("pages", mapper.valueToTree(pages));
		reply(200, envelope(json.toString()));
		String largestJpeg = "data:image/jpeg;base64," + "A".repeat(4 * 1024 * 1024);
		assertEquals(4, client.analyze(List.of(largestJpeg, largestJpeg, largestJpeg, largestJpeg),
				List.of(media.get(0), media.get(0), media.get(0), media.get(0)), List.of()).pages().size());
		verify(http).sendAsync(argThat(request -> {
			assertTrue(request.bodyPublisher().orElseThrow().contentLength() < 20_000_000,
					"Four processed JPEGs at the 3 MiB cap must fit the inline request limit");
			return true;
		}), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void rejectsAnOversizedSerializedInlineRequestBeforeNetwork() {
		String image = "data:image/jpeg;base64," + "A".repeat(20_000_000);
		assertTrue(status(422, () -> client.analyze(List.of(image), media, List.of())).getReason().contains("较小版本"));
		verifyNoInteractions(http);
	}

	@Test
	void preservesTextReadingOrderOriginalTranslationNotesAndEmptyPages() {
		var entries = List.of(java.util.Map.of("source", "こんにちは！", "translation", "你好！", "note", "右上对话框"),
				java.util.Map.of("source", "[无法辨认]", "translation", "[无法辨认]", "note", "左侧旁白模糊"));
		var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(RESULT);
		json.set("pages", mapper.valueToTree(List.of(java.util.Map.of("index", 1, "description", "交谈", "texts", entries),
				java.util.Map.of("index", 2, "description", "无文字画面", "texts", List.of()))));
		reply(200, envelope(json.toString()));
		var pages = client.analyze(List.of(IMAGE, IMAGE), List.of(media.get(0), new Media(13, 3, "image/jpeg")), List.of()).pages();
		assertEquals(2, pages.get(0).texts().size());
		assertEquals("こんにちは！", pages.get(0).texts().get(0).source());
		assertEquals("你好！", pages.get(0).texts().get(0).translation());
		assertEquals("右上对话框", pages.get(0).texts().get(0).note());
		assertEquals("[无法辨认]", pages.get(0).texts().get(1).source());
		assertEquals("[无法辨认]", pages.get(0).texts().get(1).translation());
		assertEquals("左侧旁白模糊", pages.get(0).texts().get(1).note());
		assertTrue(pages.get(1).texts().isEmpty());
	}

	@Test
	void rejectsMissingMalformedOrExcessivePageTexts() {
		String entry = "{\"source\":\"Hello\",\"translation\":\"你好\",\"note\":\"右上对话框\"}";
		for (String texts : List.of("null", "{}", "[null]", "[\"Hello\"]", "[{\"source\":\"Hello\",\"translation\":\"你好\"}]",
				"[" + entry.replace("\"Hello\"", "22") + "]", "[" + entry.replace("\"你好\"", "null") + "]",
				"[" + entry.replace("\"右上对话框\"", "[]") + "]", "[" + entry.replace("\"Hello\"", "\"" + "a".repeat(2001) + "\"") + "]",
				"[" + entry.replace("\"你好\"", "\"" + "中".repeat(2001) + "\"") + "]",
				"[" + entry.replace("\"右上对话框\"", "\"" + "位".repeat(201) + "\"") + "]",
				"[" + entry.replace("}", ",\"extra\":true}") + "]", "[" + String.join(",", java.util.Collections.nCopies(41, entry)) + "]")) {
			reply(200, envelope(RESULT.replace("\"texts\":[]", "\"texts\":" + texts)));
			status(502, this::analyze);
		}
		reply(200, envelope(RESULT.replace(",\"texts\":[]", "")));
		status(502, this::analyze);
	}

	@Test
	void rejectsDisabledMissingKeyUnsupportedModelAndInvalidTimeoutWithoutRequest() {
		for (var unavailable : List.of(new GeminiClient(http, mapper, false, "key", MODEL, 90),
				new GeminiClient(http, mapper, true, "", MODEL, 90),
				new GeminiClient(http, mapper, true, "key", "gemini-3.1-pro-preview", 90),
				new GeminiClient(http, mapper, true, "key", MODEL, 0))) {
			status(503, unavailable::checkAvailable);
		}
		verifyNoInteractions(http);
	}

	@Test
	void mapsHttpFailuresAndErrorInside200WithoutLeakingUpstreamBody() {
		for (int code : List.of(400, 401, 403, 429, 404, 500, 502, 503, 504)) {
			int expected = code == 429 ? 429 : code == 504 ? 504 : code == 404 || code == 500 || code == 503 ? 503 : 502;
			reply(code, "SECRET upstream body");
			assertFalse(status(expected, this::analyze).getReason().contains("SECRET"));
			reply(200, "{\"error\":{\"code\":" + code + ",\"message\":\"SECRET\"}}");
			status(expected, this::analyze);
		}
	}

	@Test
	void rejectsInvalidJsonMissingFieldsWrongTypesPageCountIndicesAndArrayOverflow() {
		for (String invalid : List.of("not json", "null", "{}", RESULT + " {}", RESULT.replace("\"index\":1", "\"index\":2"),
				RESULT.replace("\"index\":1", "\"index\":1.0"), RESULT.replace("\"suggestedTags\":[\"漫画\"]", "\"suggestedTags\":null"),
				RESULT.replace("\"summary\":\"摘要\"", "\"summary\":22"),
				RESULT.replace("\"relationships\":[]", "\"relationships\":[" + "\"候选\",".repeat(8) + "\"候选\"]"))) {
			reply(200, envelope(invalid)); status(502, this::analyze);
		}
		reply(200, "not json"); status(502, this::analyze);
		reply(200, envelope(RESULT).replace("\"STOP\"", "\"MAX_TOKENS\"")); status(502, this::analyze);
		reply(200, envelope(RESULT));
		status(502, () -> client.analyze(List.of(IMAGE, IMAGE), List.of(media.get(0), media.get(0)), List.of()));
	}

	@Test
	void safetyBlocksMissingCandidatesEmptyPartsAndMissingFinishReasonFailSafely() {
		for (String response : List.of("null", "{}", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
				"{\"candidates\":[]}", "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}",
				envelope(RESULT).replace("\"STOP\"", "\"SAFETY\""),
				"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}")) {
			reply(200, response); status(502, this::analyze);
		}
	}

	@Test
	void joinsFinalTextPartsWithoutExposingThoughtsAndKeepsConfiguredModelWhenVersionAbsent() {
		int split = RESULT.length() / 2;
		String response = mapper.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of(
				"finishReason", "STOP", "content", java.util.Map.of("parts", List.of(
						java.util.Map.of("thought", true, "text", "private reasoning"),
						java.util.Map.of("text", RESULT.substring(0, split)),
						java.util.Map.of("text", RESULT.substring(split))))))));
		reply(200, response);
		var result = client.analyze(List.of(IMAGE), media, List.of());
		assertEquals("摘要", result.summary());
		assertEquals(MODEL, result.model());
	}

	@Test
	void networkAndHttpTimeoutFailuresAreSafe() {
		for (var error : List.of(new java.io.IOException("secret"), new HttpTimeoutException("timeout"))) {
			when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
					.thenReturn(CompletableFuture.failedFuture(error));
			var failure = status(error instanceof HttpTimeoutException ? 504 : 502, this::analyze);
			assertInstanceOf(java.util.concurrent.ExecutionException.class, failure.getCause());
			assertSame(error, failure.getCause().getCause());
		}
	}

	@Test
	void fullBodyDeadlineCancelsUnfinishedRequest() {
		var pending = new CompletableFuture<HttpResponse<String>>();
		when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
		var shortClient = new GeminiClient(http, mapper, true, "key", MODEL, 1);
		assertInstanceOf(java.util.concurrent.TimeoutException.class,
				status(504, () -> shortClient.analyze(List.of(IMAGE), media, List.of())).getCause());
		assertTrue(pending.isCancelled());
	}

	@Test
	void logsStandardGoogleErrorFieldsWhileKeepingBrowserMessageFriendly() {
		String error = googleError(400, "INVALID_ARGUMENT", "Unknown name responseFormat at generation_config");
		reply(400, error);
		var failure = status(502, this::analyze);
		String logged = onlyLog();
		assertTrue(logged.contains("httpStatus=400"));
		assertTrue(logged.contains("google.error.code=400"));
		assertTrue(logged.contains("google.error.status=INVALID_ARGUMENT"));
		assertTrue(logged.contains("google.error.message=Unknown name responseFormat at generation_config"));
		assertFalse(failure.getReason().contains("responseFormat"));
		assertNull(capturedLogs.list.get(0).getThrowableProxy());
	}

	@Test
	void errorInside200LogsActualHttpStatusAndGoogleCodeSeparately() {
		reply(200, googleError(429, "RESOURCE_EXHAUSTED", "Quota exhausted"));
		status(429, this::analyze);
		assertTrue(onlyLog().contains("httpStatus=200 google.error.code=429"));
		assertTrue(onlyLog().contains("google.error.status=RESOURCE_EXHAUSTED"));
	}

	@Test
	void malformedResponseLogsOnlySingleLineRedactedBoundedSnippet() {
		reply(502, "Bad gateway\r\nfor test-only-key " + "timeout ".repeat(200));
		status(502, this::analyze);
		String logged = onlyLog();
		assertTrue(logged.contains("httpStatus=502"));
		assertTrue(logged.contains("Bad gateway"));
		assertFalse(logged.contains("test-only-key"));
		assertFalse(logged.contains("\n"));
		assertFalse(logged.contains("\r"));
		String snippet = logged.substring(logged.indexOf("responseSnippet=") + "responseSnippet=".length());
		assertTrue(snippet.length() <= 512);
		assertTrue(snippet.endsWith("…[truncated]"));
	}

	@Test
	void reflectedHeadersPromptAndRequestBodyNeverReachLogs() {
		var fragments = List.of("Authorization: Bearer other-secret", "x-goog-api-key: other-secret",
				"GEMINI_API_KEY=other-secret", "data:image/jpeg;base64,AQ==", "inlineData: other-image");
		for (String fragment : fragments) {
			capturedLogs.list.clear();
			reply(400, googleError(400, "INVALID_ARGUMENT", fragment));
			status(502, this::analyze);
			assertTrue(onlyLog().contains("google.error.message=[request data omitted]"));
			assertFalse(onlyLog().contains(fragment));
		}
		for (boolean echoFullBody : List.of(false, true)) {
			capturedLogs.list.clear();
			when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
				String requestBody = body(call.getArgument(0));
				String prompt = mapper.readTree(requestBody).path("systemInstruction").path("parts").path(0).path("text").asText();
				@SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
				when(response.statusCode()).thenReturn(400);
				when(response.body()).thenReturn(echoFullBody ? requestBody : googleError(400, "INVALID_ARGUMENT", prompt));
				return CompletableFuture.completedFuture(response);
			});
			status(502, this::analyze);
			assertTrue(onlyLog().contains("[request data omitted]"));
			assertFalse(onlyLog().contains("用中文分析"));
			assertFalse(onlyLog().contains("inlineData"));
			assertFalse(onlyLog().contains("generationConfig"));
		}
	}

	@Test
	void bareImageDataAndLongEncodedTextAreRemovedBeforeTruncation() {
		reply(403, googleError(403, "PERMISSION_DENIED", "Image bytes AQ== and " + "Ab9/".repeat(100)));
		status(502, this::analyze);
		assertFalse(onlyLog().contains("AQ=="));
		assertFalse(onlyLog().contains("Ab9/"));
		assertTrue(onlyLog().contains("[image omitted]"));
		assertTrue(onlyLog().contains("[encoded data omitted]"));
	}

	@Test
	void nestedNetworkRootCauseIsLoggedSafelyAndOriginalChainIsRetained() {
		var root = new javax.net.ssl.SSLHandshakeException("Handshake failed for test-only-key\nAQ==");
		var io = new java.io.IOException("transport failure", root);
		when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
				.thenReturn(CompletableFuture.failedFuture(io));
		var failure = status(502, this::analyze);
		assertSame(io, failure.getCause().getCause());
		assertSame(root, failure.getCause().getCause().getCause());
		String logged = onlyLog();
		assertTrue(logged.contains("exceptionClass=java.util.concurrent.ExecutionException"));
		assertTrue(logged.contains("rootCauseClass=javax.net.ssl.SSLHandshakeException"));
		assertTrue(logged.contains("rootCauseMessage=Handshake failed for [REDACTED]"));
		assertFalse(logged.contains("test-only-key"));
		assertFalse(logged.contains("AQ=="));
		assertFalse(logged.contains("\n"));
		assertNull(capturedLogs.list.get(0).getThrowableProxy(), "Raw Throwable logging would bypass redaction");
		assertFalse(failure.getReason().contains("Handshake"));
	}

	@Test
	void synchronousIoFailureIsLoggedAndRetainsCause() {
		var failure = new java.io.UncheckedIOException(new java.io.IOException("Connection refused"));
		when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(failure);
		assertSame(failure, status(502, this::analyze).getCause());
		assertTrue(onlyLog().contains("exceptionClass=java.io.UncheckedIOException"));
		assertTrue(onlyLog().contains("rootCauseClass=java.io.IOException"));
		assertTrue(onlyLog().contains("rootCauseMessage=Connection refused"));
	}

	private String googleError(int code, String status, String message) {
		return mapper.writeValueAsString(java.util.Map.of("error", java.util.Map.of("code", code, "status", status, "message", message)));
	}

	private String onlyLog() {
		assertEquals(1, capturedLogs.list.size());
		return capturedLogs.list.get(0).getFormattedMessage();
	}

	@Test
	void springCreatesDisabledClientWithDefaultConfiguration() {
		try (var factory = mockStatic(HttpClient.class); var context = new AnnotationConfigApplicationContext()) {
			factory.when(HttpClient::newHttpClient).thenReturn(http);
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(GeminiClient.class); context.refresh();
			status(503, context.getBean(GeminiClient.class)::checkAvailable);
		}
	}

	private void analyze() { client.analyze(List.of(IMAGE), media, List.of()); }
	private static ResponseStatusException status(int expected, Runnable call) {
		var error = assertThrows(ResponseStatusException.class, call::run);
		assertEquals(expected, error.getStatusCode().value()); return error;
	}
	private static String body(HttpRequest request) {
		var bytes = new ByteArrayOutputStream();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
			public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
			public void onNext(ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
			public void onError(Throwable error) { throw new AssertionError(error); }
			public void onComplete() { }
		});
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
