package pl.asenet.liliput;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LiliputAppenderTest {

    private HttpServer server;
    private LiliputAppender appender;
    private LoggerContext loggerContext;
    private BlockingQueue<String> registrations;
    private ByteArrayOutputStream stdoutCapture;
    private PrintStream originalStdout;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        registrations = new LinkedBlockingQueue<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/register", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            registrations.add(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        loggerContext = new LoggerContext();

        appender = new LiliputAppender();
        appender.setContext(loggerContext);
        appender.setRegistryEndpoint("http://localhost:" + port);
        appender.start();

        originalStdout = System.out;
        stdoutCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdoutCapture));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalStdout);
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
        var arr = new Gson().fromJson(output.substring(1), com.google.gson.JsonArray.class);
        assertEquals(1, arr.get(0).getAsLong());
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
        assertEquals(1, reg.get("tid").getAsLong());
        assertEquals("User {} has logged in", reg.get("tpl").getAsString());
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
        assertNull(second, "Should not re-register the same template");
    }

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
    void sameTemplateGetsSameId() {
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
        long id1 = gson.fromJson(lines[0].substring(1), com.google.gson.JsonArray.class).get(0).getAsLong();
        long id2 = gson.fromJson(lines[1].substring(1), com.google.gson.JsonArray.class).get(0).getAsLong();

        assertEquals(id1, id2);
    }
}
