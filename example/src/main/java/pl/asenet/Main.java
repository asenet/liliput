package pl.asenet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final String[] USERS = {"alice", "bob", "charlie", "dave", "eve"};
    private static final String[] MODULES = {"payments", "auth", "orders", "shipping", "inventory"};
    private static final String[] ACTIONS = {"login", "logout", "purchase", "refund", "browse"};
    private static final String[] ENDPOINTS = {"/api/users", "/api/orders", "/api/products", "/api/health", "/api/search"};

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(2000);

        var random = new java.util.Random();
        int targetPerSecond = 1000;
        long intervalNanos = 1_000_000_000L / targetPerSecond;

        long count = 0;
        long startTime = System.nanoTime();

        while (true) {
            switch (random.nextInt(6)) {
                case 0 -> log.info("User {} has logged in", USERS[random.nextInt(USERS.length)]);
                case 1 -> log.info("Order {} processed", random.nextInt(100_000));
                case 2 -> log.warn("Error in module {}", MODULES[random.nextInt(MODULES.length)]);
                case 3 -> log.info("User {} performed {} on order {}", USERS[random.nextInt(USERS.length)], ACTIONS[random.nextInt(ACTIONS.length)], random.nextInt(100_000));
                case 4 -> log.debug("GET {} responded in {} ms", ENDPOINTS[random.nextInt(ENDPOINTS.length)], random.nextInt(500));
                case 5 -> log.error("Timeout calling {} after {} ms", ENDPOINTS[random.nextInt(ENDPOINTS.length)], 5000 + random.nextInt(5000));
            }

            count++;

            // Pace to ~1000/s using busy-wait for precision
            long expectedTime = startTime + count * intervalNanos;
            while (System.nanoTime() < expectedTime) {
                Thread.onSpinWait();
            }
        }
    }
}
