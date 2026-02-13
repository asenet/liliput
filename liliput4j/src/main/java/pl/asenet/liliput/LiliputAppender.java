package pl.asenet.liliput;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LiliputAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String DEFAULT_REGISTRY_ENDPOINT =
            "http://localhost:3000/api/plugins/liliput-datasource/resources";

    private final TemplateRegistry registry = new TemplateRegistry();
    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private Set<Level> compressLevels;

    // --- Configurable properties (set via logback.xml) ---

    private String registryEndpoint = DEFAULT_REGISTRY_ENDPOINT;
    private int resyncIntervalSeconds = 30;
    private int connectTimeoutSeconds = 5;
    private boolean registrationEnabled = true;
    private String levels = "";

    public void setRegistryEndpoint(String registryEndpoint) {
        this.registryEndpoint = registryEndpoint;
    }

    public String getRegistryEndpoint() {
        return registryEndpoint;
    }

    public void setResyncIntervalSeconds(int resyncIntervalSeconds) {
        this.resyncIntervalSeconds = resyncIntervalSeconds;
    }

    public int getResyncIntervalSeconds() {
        return resyncIntervalSeconds;
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

    public TemplateRegistry getRegistry() {
        return registry;
    }

    @Override
    public void start() {
        compressLevels = parseLevels(levels);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();

        if (registrationEnabled && resyncIntervalSeconds > 0) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "liliput-resync");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::resyncAllTemplates,
                    resyncIntervalSeconds, resyncIntervalSeconds, TimeUnit.SECONDS);
        }

        super.start();
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
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

        boolean isNewTemplate = registry.isNew(template);
        long templateId = registry.getOrRegister(template);

        if (registrationEnabled && isNewTemplate) {
            registerTemplate(templateId, template);
        }

        Object[] args = event.getArgumentArray();
        List<Object> compact = new ArrayList<>();
        compact.add(templateId);
        if (args != null) {
            Collections.addAll(compact, args);
        }

        String levelPrefix = event.getLevel().toString().substring(0, 1);
        System.out.println(levelPrefix + gson.toJson(compact));
    }

    private void resyncAllTemplates() {
        registry.forEach(this::registerTemplate);
    }

    private void registerTemplate(long tid, String template) {
        String json = gson.toJson(Map.of("tid", tid, "tpl", template));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registryEndpoint + "/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionally(ex -> {
                    addWarn("Failed to register template #" + tid + ": " + ex.getMessage());
                    return null;
                });
    }

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
            if (!trimmed.isEmpty()) {
                Level level = Level.toLevel(trimmed, null);
                if (level != null) {
                    result.add(level);
                }
            }
        }

        return result.isEmpty() ? null : result;
    }
}
