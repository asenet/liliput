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
    void firstRegistrationReturnsId1() {
        long id = registry.getOrRegister("User {} logged in");
        assertEquals(1, id);
    }

    @Test
    void sameTemplateReturnsSameId() {
        long id1 = registry.getOrRegister("User {} logged in");
        long id2 = registry.getOrRegister("User {} logged in");
        assertEquals(id1, id2);
    }

    @Test
    void differentTemplatesGetDifferentIds() {
        long id1 = registry.getOrRegister("User {} logged in");
        long id2 = registry.getOrRegister("Order {} processed");
        assertNotEquals(id1, id2);
    }

    @Test
    void reverseLookupreturnsCorrectTemplate() {
        registry.getOrRegister("User {} logged in");
        assertEquals("User {} logged in", registry.getTemplate(1));
    }

    @Test
    void unknownIdReturnsNull() {
        assertNull(registry.getTemplate(999));
    }

    @Test
    void isNewReturnsTrueForUnseenTemplate() {
        assertTrue(registry.isNew("User {} logged in"));
    }

    @Test
    void isNewReturnsFalseAfterRegistration() {
        registry.getOrRegister("User {} logged in");
        assertFalse(registry.isNew("User {} logged in"));
    }

    @Test
    void sizeReflectsRegistrations() {
        assertEquals(0, registry.size());
        registry.getOrRegister("A");
        registry.getOrRegister("B");
        registry.getOrRegister("A");
        assertEquals(2, registry.size());
    }

    @Test
    void clearResetsState() {
        registry.getOrRegister("A");
        registry.clear();
        assertEquals(0, registry.size());
        assertTrue(registry.isNew("A"));
    }

    @Test
    void concurrentRegistrationOfSameTemplateAssignsOneId() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<Long> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ids.add(registry.getOrRegister("User {} logged in"));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(1, ids.size(), "All threads should get the same ID");
        assertEquals(1, registry.size());
    }
}
