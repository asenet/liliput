package pl.asenet.liliput;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TemplateRegistryTest {

    private TemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TemplateRegistry();
    }

    @Test
    void getCachedId_returnsNullForUnknownTemplate() {
        assertNull(registry.getCachedId("User {} logged in"));
    }

    @Test
    void cache_storesServerAssignedId() {
        registry.cache("User {} logged in", 42);
        assertEquals(42, registry.getCachedId("User {} logged in"));
    }

    @Test
    void cache_reverseLookupWorks() {
        registry.cache("User {} logged in", 42);
        assertEquals("User {} logged in", registry.getTemplate(42));
    }

    @Test
    void cache_sameTemplateOverwritesId() {
        registry.cache("User {} logged in", 1);
        registry.cache("User {} logged in", 42);
        assertEquals(42, registry.getCachedId("User {} logged in"));
    }

    @Test
    void differentTemplatesGetDifferentCachedIds() {
        registry.cache("User {} logged in", 1);
        registry.cache("Order {} processed", 2);

        assertEquals(1, registry.getCachedId("User {} logged in"));
        assertEquals(2, registry.getCachedId("Order {} processed"));
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(registry.getTemplate(999));
    }

    @Test
    void sizeReflectsCachedEntries() {
        assertEquals(0, registry.size());
        registry.cache("A", 1);
        registry.cache("B", 2);
        registry.cache("A", 1); // duplicate — no size change
        assertEquals(2, registry.size());
    }

    @Test
    void clearResetsState() {
        registry.cache("A", 1);
        registry.clear();
        assertEquals(0, registry.size());
        assertNull(registry.getCachedId("A"));
        assertNull(registry.getTemplate(1));
    }

    @Test
    void concurrentCacheAccessIsSafe() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (int i = 0; i < threadCount; i++) {
            final long id = 42;
            executor.submit(() -> {
                try {
                    registry.cache("User {} logged in", id);
                    ids.add(registry.getCachedId("User {} logged in"));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, ids.size(), "All threads should see the same cached ID");
        assertEquals(42L, ids.iterator().next());
    }
}
