package pl.asenet.liliput;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class TemplateRegistry {

    private final ConcurrentHashMap<String, Long> templateToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> idToTemplate = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    public long getOrRegister(String template) {
        return templateToId.computeIfAbsent(template, t -> {
            long id = counter.incrementAndGet();
            idToTemplate.put(id, t);
            return id;
        });
    }

    public boolean isNew(String template) {
        return !templateToId.containsKey(template);
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
        counter.set(0);
    }
}
