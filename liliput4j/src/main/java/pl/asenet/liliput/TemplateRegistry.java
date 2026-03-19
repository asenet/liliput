package pl.asenet.liliput;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Local cache of server-assigned template IDs.
 * <p>
 * The server (Grafana plugin) is the single source of truth for template IDs.
 * This class caches the mapping so that only the first occurrence of each template
 * requires a network call.
 */
public class TemplateRegistry {

    private final ConcurrentHashMap<String, Long> templateToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> idToTemplate = new ConcurrentHashMap<>();

    /**
     * Returns the cached server-assigned ID for a template, or null if not yet registered.
     */
    public Long getCachedId(String template) {
        return templateToId.get(template);
    }

    /**
     * Caches a server-assigned template ID locally.
     */
    public void cache(String template, long serverAssignedId) {
        templateToId.put(template, serverAssignedId);
        idToTemplate.put(serverAssignedId, template);
    }

    public String getTemplate(long id) {
        return idToTemplate.get(id);
    }

    public int size() {
        return templateToId.size();
    }

    public void forEach(BiConsumer<Long, String> action) {
        idToTemplate.forEach(action);
    }

    public void clear() {
        templateToId.clear();
        idToTemplate.clear();
    }
}
