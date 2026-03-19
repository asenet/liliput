package pl.asenet.liliput;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class LiliputAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String DEFAULT_REGISTRY_ENDPOINT =
            "http://localhost:3000/api/plugins/liliput-datasource/resources";

    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;

    private final TemplateRegistry registry = new TemplateRegistry();
    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private Set<Level> compressLevels;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenedAt = 0;
    private long halfOpenDelayMs = 30_000; // 30 seconds before probe

    // --- Configurable properties (set via logback.xml) ---

    private String registryEndpoint = DEFAULT_REGISTRY_ENDPOINT;
    private int connectTimeoutSeconds = 5;
    private boolean registrationEnabled = true;
    private String levels = "";
    private int circuitBreakerThreshold = CIRCUIT_BREAKER_THRESHOLD;

    public void setRegistryEndpoint(String registryEndpoint) {
        this.registryEndpoint = registryEndpoint;
    }

    public String getRegistryEndpoint() {
        return registryEndpoint;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    public void setLevels(String levels) {
        this.levels = levels;
    }

    public String getLevels() {
        return levels;
    }

    public void setCircuitBreakerThreshold(int circuitBreakerThreshold) {
        this.circuitBreakerThreshold = circuitBreakerThreshold;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public TemplateRegistry getRegistry() {
        return registry;
    }

    boolean isCircuitOpen() {
        return circuitOpen;
    }

    void setHalfOpenDelayMs(long halfOpenDelayMs) {
        this.halfOpenDelayMs = halfOpenDelayMs;
    }

    @Override
    public void start() {
        compressLevels = parseLevels(levels);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();

        super.start();
    }

    @Override
    public void stop() {
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String template = event.getMessage();
        if (template == null) {
            return;
        }

        // If levels filter is set and this level is not in it, pass through as plain text.
        if (compressLevels != null && !compressLevels.contains(event.getLevel())) {
            System.out.println(event.getFormattedMessage());
            return;
        }

        // If registration is disabled, always output plain text.
        if (!registrationEnabled) {
            printFallbackLog(event);
            return;
        }

        // Check local cache for server-assigned template ID.
        Long templateId = registry.getCachedId(template);

        if (templateId == null) {
            // New template — need to register with server to get an ID.
            templateId = tryRegisterTemplate(template);
            if (templateId == null) {
                // Registration failed — fall back to plain text for this log.
                printFallbackLog(event);
                return;
            }
        }

        // Build compact log line with server-assigned ID.
        Object[] args = event.getArgumentArray();
        ArrayList<Object> compact = new ArrayList<>();
        compact.add(templateId);
        if (args != null) {
            for (Object arg : args) {
                // Stringify complex objects (Maps, Collections, arrays) so Gson serializes
                // them as JSON strings, not JSON objects/arrays. This prevents the Go side
                // from confusing a Map param with an MDC object.
                if (arg instanceof Map<?, ?> || arg instanceof Collection<?> ||
                        (arg != null && arg.getClass().isArray())) {
                    compact.add(String.valueOf(arg));
                } else {
                    compact.add(arg);
                }
            }
        }

        // MDC support: compress MDC keys into a registered schema.
        // Instead of {"traceId":"a1","userId":"alice"} we send [mdcSchemaId,"a1","alice"]
        // where mdcSchemaId refers to the registered key set "traceId,userId".
        Map<String, String> mdcMap = safeGetMdc(event);
        if (mdcMap != null && !mdcMap.isEmpty()) {
            var sortedKeys = new java.util.TreeSet<>(mdcMap.keySet());
            String schemaKey = String.join(",", sortedKeys);

            Long mdcSchemaId = registry.getCachedId("@mdc:" + schemaKey);
            if (mdcSchemaId == null) {
                mdcSchemaId = tryRegisterTemplate("@mdc:" + schemaKey);
            }

            if (mdcSchemaId != null) {
                // Compressed MDC: [schemaId, value1, value2, ...]
                ArrayList<Object> compressedMdc = new ArrayList<>();
                compressedMdc.add(mdcSchemaId);
                for (String key : sortedKeys) {
                    compressedMdc.add(mdcMap.get(key));
                }
                compact.add(compressedMdc);
            } else {
                // Fallback: send full MDC JSON object (uncompressed)
                compact.add(mdcMap);
            }
        }

        // Exception handling: append stack trace as raw string
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            compact.add(ThrowableProxyUtil.asString(throwableProxy));
        }

        String levelPrefix = event.getLevel().toString().substring(0, 1);
        System.out.println(levelPrefix + gson.toJson(compact));
    }

    private void printFallbackLog(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.getLevel().toString());
        sb.append(' ');
        sb.append(event.getFormattedMessage());

        Map<String, String> mdcMap = safeGetMdc(event);
        if (mdcMap != null && !mdcMap.isEmpty()) {
            sb.append(" MDC=").append(mdcMap);
        }

        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            // Replace newlines with escaped \n to keep the entire log as a single line.
            // This prevents Loki (which splits on newlines) from fragmenting the stack trace
            // into separate log entries.
            String trace = ThrowableProxyUtil.asString(throwableProxy);
            sb.append(" | ").append(trace.replace("\n", "\\n").replace("\r", ""));
        }

        System.out.println(sb);
    }

    /**
     * Attempts to register a template with the server and returns the server-assigned ID,
     * or null if registration failed (server unreachable, circuit open, etc.).
     */
    private Long tryRegisterTemplate(String template) {
        // Circuit breaker: skip registration if open (unless half-open probe)
        if (circuitOpen && !shouldAttemptHalfOpenProbe()) {
            return null;
        }

        return registerTemplateSync(template);
    }

    /**
     * Synchronous HTTP POST to register a template with the server.
     * The server assigns the ID and returns it in the response.
     * On success, the ID is cached locally.
     */
    private Long registerTemplateSync(String template) {
        String json = gson.toJson(Map.of("tpl", template));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registryEndpoint + "/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Parse server-assigned ID from response.
                @SuppressWarnings("unchecked")
                Map<String, Object> body = gson.fromJson(response.body(), Map.class);
                Number tid = (Number) body.get("tid");
                if (tid != null) {
                    long id = tid.longValue();
                    registry.cache(template, id);
                    onRegistrationSuccess();
                    return id;
                }
            }
            onRegistrationFailure(template, "HTTP " + response.statusCode());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onRegistrationFailure(template, e.getMessage());
            return null;
        } catch (Exception e) {
            onRegistrationFailure(template, e.getMessage());
            return null;
        }
    }

    private void onRegistrationSuccess() {
        int prev = consecutiveFailures.getAndSet(0);
        if (prev >= circuitBreakerThreshold) {
            circuitOpen = false;
            addInfo("Circuit breaker closed: registry endpoint is reachable again");
        }
    }

    private void onRegistrationFailure(String template, String reason) {
        addWarn("Failed to register template '" + template + "': " + reason);
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitBreakerThreshold && !circuitOpen) {
            circuitOpen = true;
            circuitOpenedAt = System.currentTimeMillis();
            addWarn("Circuit breaker opened after " + failures
                    + " consecutive failures. Falling back to raw log output.");
        }
    }

    /**
     * In half-open state, allows one probe attempt after halfOpenDelayMs has passed.
     */
    private boolean shouldAttemptHalfOpenProbe() {
        if (!circuitOpen) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - circuitOpenedAt;
        if (elapsed < halfOpenDelayMs) {
            return false;
        }
        // Reset the timer so we don't flood probes
        circuitOpenedAt = System.currentTimeMillis();
        return true;
    }

    private static Map<String, String> safeGetMdc(ILoggingEvent event) {
        try {
            return event.getMDCPropertyMap();
        } catch (Exception e) {
            return null;
        }
    }

    private static final Set<String> KNOWN_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    /**
     * Parses a comma-separated list of level names into a Set.
     * Returns null if empty (meaning all levels are compressed).
     */
    static Set<Level> parseLevels(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        Set<Level> result = new java.util.HashSet<>();
        for (String part : input.split(",")) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty() && KNOWN_LEVELS.contains(trimmed)) {
                result.add(Level.toLevel(trimmed));
            }
        }

        return result.isEmpty() ? null : result;
    }
}
