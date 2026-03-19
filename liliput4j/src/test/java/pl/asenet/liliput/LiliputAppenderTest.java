package pl.asenet.liliput;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LiliputAppenderTest {

    private HttpServer server;
    private LiliputAppender appender;
    private LoggerContext loggerContext;
    private BlockingQueue<String> registrations;
    private ByteArrayOutputStream stdoutCapture;
    private PrintStream originalStdout;
    private int port;

    // Server-side state: tracks registered templates and assigns IDs
    private ConcurrentHashMap<String, Long> serverTemplates;
    private AtomicLong serverNextId;

    @BeforeEach
    void setUp() throws IOException {
        registrations = new LinkedBlockingQueue<>();
        serverTemplates = new ConcurrentHashMap<>();
        serverNextId = new AtomicLong(0);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/register", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String jsonBody = new String(body, StandardCharsets.UTF_8);
            registrations.add(jsonBody);

            // Parse template from request and assign server ID
            JsonObject req = new Gson().fromJson(jsonBody, JsonObject.class);
            String tpl = req.get("tpl").getAsString();
            long tid = serverTemplates.computeIfAbsent(tpl, k -> serverNextId.incrementAndGet());

            // Return the server-assigned ID
            String response = new Gson().toJson(java.util.Map.of("tid", tid));
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        // Use the actual LoggerFactory context so MDC adapter is initialized
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        appender = new LiliputAppender();
        appender.setContext(loggerContext);
        appender.setRegistryEndpoint("http://localhost:" + port);
        appender.start();

        originalStdout = System.out;
        stdoutCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdoutCapture));

        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalStdout);
        MDC.clear();
        appender.stop();
        server.stop(0);
    }

    @Test
    void writesCompactJsonToStdout() {
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        assertEquals('I', output.charAt(0));
        var arr = new Gson().fromJson(output.substring(1), JsonArray.class);
        assertEquals(1, arr.get(0).getAsLong(), "Template ID should be server-assigned 1");
        assertEquals("alice", arr.get(1).getAsString());
    }

    @Test
    void registersNewTemplateViaHttp() throws InterruptedException {
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );

        appender.append(event);

        String json = registrations.poll(5, TimeUnit.SECONDS);
        assertNotNull(json, "Should have sent a registration request");

        JsonObject reg = new Gson().fromJson(json, JsonObject.class);
        // Server-generated IDs: request should only contain template text, no tid
        assertFalse(reg.has("tid"), "Request should NOT contain tid (server assigns it)");
        assertEquals("User {} has logged in", reg.get("tpl").getAsString());
    }

    @Test
    void serverAssignedIdIsUsedInOutput() {
        // First template gets server ID 1, second gets 2
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        ));
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Order {} processed",
                null,
                new Object[]{42}
        ));

        Gson gson = new Gson();
        String[] lines = stdoutCapture.toString().trim().split("\n");
        assertEquals(2, lines.length);

        long id1 = gson.fromJson(lines[0].substring(1), JsonArray.class).get(0).getAsLong();
        long id2 = gson.fromJson(lines[1].substring(1), JsonArray.class).get(0).getAsLong();

        assertEquals(1, id1, "First template should get server ID 1");
        assertEquals(2, id2, "Second template should get server ID 2");
    }

    @Test
    void doesNotReRegisterKnownTemplate() throws InterruptedException {
        LoggingEvent event1 = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        LoggingEvent event2 = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"bob"}
        );

        appender.append(event1);
        appender.append(event2);

        String first = registrations.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);

        String second = registrations.poll(1, TimeUnit.SECONDS);
        assertNull(second, "Should not re-register the same template (cached locally)");
    }

    @Test
    void sameTemplateGetsSameServerAssignedId() {
        LoggingEvent event1 = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        LoggingEvent event2 = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"bob"}
        );

        appender.append(event1);
        appender.append(event2);

        Gson gson = new Gson();
        String[] lines = stdoutCapture.toString().trim().split("\n");
        long id1 = gson.fromJson(lines[0].substring(1), JsonArray.class).get(0).getAsLong();
        long id2 = gson.fromJson(lines[1].substring(1), JsonArray.class).get(0).getAsLong();

        assertEquals(id1, id2, "Same template should reuse cached server ID");
    }

    @Test
    void multipleInstancesGetConsistentIds() throws InterruptedException {
        // Simulate two app instances registering the same template
        // The mock server returns the same ID for the same template text
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        ));

        // Second appender (simulating another instance)
        LiliputAppender appender2 = new LiliputAppender();
        appender2.setContext(loggerContext);
        appender2.setRegistryEndpoint("http://localhost:" + port);
        appender2.start();

        appender2.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"bob"}
        ));

        appender2.stop();

        Gson gson = new Gson();
        String[] lines = stdoutCapture.toString().trim().split("\n");
        long id1 = gson.fromJson(lines[0].substring(1), JsonArray.class).get(0).getAsLong();
        long id2 = gson.fromJson(lines[1].substring(1), JsonArray.class).get(0).getAsLong();

        assertEquals(id1, id2, "Both instances should get the same server-assigned ID for the same template");
    }

    // --- Level Filtering Tests ---

    @Test
    void levelsFilter_compressesOnlyMatchingLevels() {
        appender.stop();
        appender.setLevels("INFO");
        appender.start();

        LoggingEvent infoEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        LoggingEvent warnEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.WARN,
                "Error in module {}",
                null,
                new Object[]{"payments"}
        );

        appender.append(infoEvent);
        appender.append(warnEvent);

        String[] lines = stdoutCapture.toString().trim().split("\n");
        assertEquals(2, lines.length);

        // INFO should be compact
        assertEquals('I', lines[0].charAt(0));
        assertTrue(lines[0].startsWith("I["));

        // WARN should be plain text (not compressed)
        assertEquals("Error in module payments", lines[1]);
    }

    @Test
    void levelsFilter_emptyMeansAll() {
        appender.stop();
        appender.setLevels("");
        appender.start();

        LoggingEvent warnEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.WARN,
                "Error in module {}",
                null,
                new Object[]{"payments"}
        );

        appender.append(warnEvent);

        String output = stdoutCapture.toString().trim();
        assertTrue(output.startsWith("W["), "Empty levels should compress all: " + output);
    }

    @Test
    void levelsFilter_multipleValues() {
        appender.stop();
        appender.setLevels("INFO, WARN");
        appender.start();

        LoggingEvent infoEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        LoggingEvent debugEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.DEBUG,
                "Debug msg {}",
                null,
                new Object[]{"x"}
        );

        appender.append(infoEvent);
        appender.append(debugEvent);

        String[] lines = stdoutCapture.toString().trim().split("\n");
        assertTrue(lines[0].startsWith("I["));
        assertEquals("Debug msg x", lines[1]);
    }

    @Test
    void parseLevels_handlesVariousFormats() {
        assertNull(LiliputAppender.parseLevels(""));
        assertNull(LiliputAppender.parseLevels(null));
        assertNull(LiliputAppender.parseLevels("  "));

        var single = LiliputAppender.parseLevels("INFO");
        assertEquals(1, single.size());
        assertTrue(single.contains(Level.INFO));

        var multi = LiliputAppender.parseLevels("info, WARN, error");
        assertEquals(3, multi.size());
        assertTrue(multi.contains(Level.INFO));
        assertTrue(multi.contains(Level.WARN));
        assertTrue(multi.contains(Level.ERROR));
    }

    @Test
    void parseLevels_ignoresUnknownLevels() {
        var result = LiliputAppender.parseLevels("INFO, BLAH");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(Level.INFO));
        assertFalse(result.contains(Level.DEBUG));
    }

    @Test
    void parseLevels_allUnknownReturnsNull() {
        assertNull(LiliputAppender.parseLevels("BLAH, FOO"));
    }

    // --- Circuit Breaker Tests ---

    @Test
    void circuitBreaker_fallsBackToRawLogAfterConsecutiveFailures() {
        // Use an unreachable endpoint
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(2);
        appender.start();

        // Send enough distinct templates to trigger the circuit breaker.
        // Each registration is now synchronous and will fail, incrementing the failure counter.
        for (int i = 0; i < 3; i++) {
            appender.append(new LoggingEvent(
                    "pl.asenet.Test",
                    loggerContext.getLogger("test"),
                    Level.INFO,
                    "Template " + i + " user {}",
                    null,
                    new Object[]{"alice"}
            ));
        }

        assertTrue(appender.isCircuitOpen(), "Circuit should be open after sync failures");

        // Now send another new template — should produce raw fallback output
        stdoutCapture.reset();
        LoggingEvent fallbackEvent = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.WARN,
                "Fallback message {}",
                null,
                new Object[]{"test"}
        );
        appender.append(fallbackEvent);

        String output = stdoutCapture.toString().trim();
        assertTrue(output.startsWith("WARN"), "Fallback should use full level name: " + output);
        assertTrue(output.contains("Fallback message test"), "Fallback should contain formatted message");
    }

    @Test
    void circuitBreaker_cachedTemplatesStillWorkWhenCircuitOpen() {
        // Register a template while server is up
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        ));

        String firstOutput = stdoutCapture.toString().trim();
        assertTrue(firstOutput.startsWith("I["), "Should be compact while server is up");

        // Now switch to unreachable endpoint and trigger circuit breaker
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(1);
        appender.start();

        // Trigger circuit breaker with a NEW template
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "New template {}",
                null,
                new Object[]{"x"}
        ));
        assertTrue(appender.isCircuitOpen());

        // But the CACHED template should still produce compact output!
        stdoutCapture.reset();
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"bob"}
        ));

        String cachedOutput = stdoutCapture.toString().trim();
        assertTrue(cachedOutput.startsWith("I["),
                "Cached templates should still output compact even with circuit open: " + cachedOutput);
    }

    @Test
    void circuitBreaker_recoversWhenEndpointBecomesReachable() throws InterruptedException {
        // Start with unreachable endpoint and immediate half-open probing
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(2);
        appender.setHalfOpenDelayMs(0); // no delay for test
        appender.start();

        // Trigger circuit breaker with distinct templates
        for (int i = 0; i < 3; i++) {
            appender.append(new LoggingEvent(
                    "pl.asenet.Test",
                    loggerContext.getLogger("test"),
                    Level.INFO,
                    "CB-recover-trigger-" + i + " {}",
                    null,
                    new Object[]{"x"}
            ));
        }
        assertTrue(appender.isCircuitOpen(), "Circuit should be open after failures");

        // Switch to working endpoint
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:" + port);
        appender.start();

        // Send a new template — half-open probe should trigger registration on the working endpoint
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Recovery probe template {}",
                null,
                new Object[]{"probe"}
        ));

        // The sync registration should have succeeded and closed the circuit
        assertFalse(appender.isCircuitOpen(), "Circuit should be closed after successful registration");

        String regJson = registrations.poll(5, TimeUnit.SECONDS);
        assertNotNull(regJson, "Half-open probe should have sent a registration request");
    }

    // --- MDC Support Tests ---

    @Test
    void mdcSupport_includesMdcInCompactPayload() {
        MDC.put("traceId", "abc-123");
        MDC.put("userId", "alice");

        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        // [templateId, "alice", {mdc map}]
        assertEquals(3, arr.size());
        assertTrue(arr.get(2).isJsonObject(), "Third element should be MDC map");
        JsonObject mdc = arr.get(2).getAsJsonObject();
        assertEquals("abc-123", mdc.get("traceId").getAsString());
        assertEquals("alice", mdc.get("userId").getAsString());
    }

    @Test
    void mdcSupport_omittedWhenEmpty() {
        MDC.clear();

        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        // [templateId, "alice"] - no MDC
        assertEquals(2, arr.size());
    }

    @Test
    void mdcSupport_includedInFallbackLog() {
        MDC.put("traceId", "trace-456");

        // Force circuit open by using unreachable endpoint
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(1);
        appender.start();

        // Trigger circuit breaker
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Trigger {}",
                null,
                new Object[]{"x"}
        ));

        assertTrue(appender.isCircuitOpen());

        stdoutCapture.reset();
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        appender.append(event);

        String output = stdoutCapture.toString().trim();
        assertTrue(output.contains("MDC="), "Fallback should include MDC: " + output);
        assertTrue(output.contains("traceId"), "Fallback should include traceId: " + output);
    }

    // --- Exception Handling Tests ---

    @Test
    void exceptionHandling_appendsStackTraceToCompactPayload() {
        RuntimeException exception = new RuntimeException("Something broke");

        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.ERROR,
                "Processing failed for user {}",
                exception,
                new Object[]{"alice"}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        // [templateId, "alice", stackTraceString]
        assertTrue(arr.size() >= 3, "Should have at least 3 elements: " + arr);
        String stackTrace = arr.get(arr.size() - 1).getAsString();
        assertTrue(stackTrace.contains("RuntimeException"), "Should contain exception class: " + stackTrace);
        assertTrue(stackTrace.contains("Something broke"), "Should contain exception message: " + stackTrace);
    }

    @Test
    void exceptionHandling_withMdcAndException() {
        MDC.put("traceId", "trace-789");
        RuntimeException exception = new RuntimeException("Oops");

        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.ERROR,
                "Failed for {}",
                exception,
                new Object[]{"bob"}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        // [templateId, "bob", {mdc}, stackTrace]
        assertEquals(4, arr.size());
        assertEquals(1, arr.get(0).getAsLong());
        assertEquals("bob", arr.get(1).getAsString());
        assertTrue(arr.get(2).isJsonObject(), "Third should be MDC map");
        assertTrue(arr.get(3).getAsString().contains("Oops"), "Fourth should be stack trace");
    }

    @Test
    void exceptionHandling_includedInFallbackLog() {
        // Force circuit open
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(1);
        appender.start();

        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Trigger {}",
                null,
                new Object[]{"x"}
        ));

        stdoutCapture.reset();
        RuntimeException exception = new RuntimeException("Connection refused");
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.ERROR,
                "Database error for {}",
                exception,
                new Object[]{"alice"}
        );
        appender.append(event);

        String output = stdoutCapture.toString();
        assertTrue(output.contains("ERROR"), "Fallback should have level");
        assertTrue(output.contains("Database error for alice"), "Fallback should have message");
        assertTrue(output.contains("Connection refused"), "Fallback should have exception");
    }

    // --- Map/Collection param safety tests ---

    @Test
    void mapParam_serializedAsStringNotJsonObject() {
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Request body: {}",
                null,
                new Object[]{java.util.Map.of("key", "value")}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        assertEquals(2, arr.size(), "Should be [templateId, stringifiedMap]: " + arr);
        assertTrue(arr.get(1).isJsonPrimitive(), "Map param should be serialized as string: " + arr);
        assertFalse(arr.get(1).isJsonObject(), "Map param must NOT be a JSON object: " + arr);
    }

    @Test
    void collectionParam_serializedAsStringNotJsonArray() {
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Items: {}",
                null,
                new Object[]{java.util.List.of("a", "b", "c")}
        );

        appender.append(event);

        String output = stdoutCapture.toString().trim();
        JsonArray arr = new Gson().fromJson(output.substring(1), JsonArray.class);

        assertEquals(2, arr.size());
        assertTrue(arr.get(1).isJsonPrimitive(), "Collection param should be a string: " + arr);
    }

    @Test
    void fallbackLog_singleLineWithEscapedStackTrace() {
        appender.stop();
        appender.setRegistryEndpoint("http://localhost:1");
        appender.setCircuitBreakerThreshold(1);
        appender.start();

        // Trigger circuit breaker
        appender.append(new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "Trigger {}",
                null,
                new Object[]{"x"}
        ));

        stdoutCapture.reset();
        RuntimeException exception = new RuntimeException("Boom");
        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.ERROR,
                "Failed for {}",
                exception,
                new Object[]{"alice"}
        );
        appender.append(event);

        String output = stdoutCapture.toString().trim();
        assertFalse(output.contains("\n"), "Fallback should be single-line, got: " + output);
        assertTrue(output.contains("ERROR"), "Should have level prefix");
        assertTrue(output.contains("Failed for alice"), "Should have message");
        assertTrue(output.contains("Boom"), "Should have exception message");
    }

    @Test
    void registrationDisabled_outputsFallbackText() {
        appender.stop();
        appender.setRegistrationEnabled(false);
        appender.start();

        LoggingEvent event = new LoggingEvent(
                "pl.asenet.Test",
                loggerContext.getLogger("test"),
                Level.INFO,
                "User {} has logged in",
                null,
                new Object[]{"alice"}
        );
        appender.append(event);

        String output = stdoutCapture.toString().trim();
        assertTrue(output.startsWith("INFO"), "Disabled registration should output fallback: " + output);
        assertTrue(output.contains("User alice has logged in"));
    }

    // --- Helper ---

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
