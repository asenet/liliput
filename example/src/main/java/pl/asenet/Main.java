package pl.asenet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    enum Industry {
        BANKING, TELCO, ECOMMERCE, IOT, GAMING, INFRA;

        static java.util.Set<Industry> parse(String env) {
            if (env == null || env.isBlank() || env.equalsIgnoreCase("all")) {
                return java.util.EnumSet.allOf(Industry.class);
            }
            var result = java.util.EnumSet.noneOf(Industry.class);
            for (String part : env.split("[,;|\\s]+")) {
                String normalized = part.trim().toUpperCase()
                        .replace("E-COMMERCE", "ECOMMERCE")
                        .replace("ADTECH", "ECOMMERCE")
                        .replace("FLEET", "IOT")
                        .replace("STREAMING", "GAMING")
                        .replace("INFRASTRUCTURE", "INFRA");
                try {
                    result.add(Industry.valueOf(normalized));
                } catch (IllegalArgumentException e) {
                    System.err.println("Unknown industry: " + part.trim() + " (available: banking, telco, ecommerce, iot, gaming, infra)");
                }
            }
            return result.isEmpty() ? java.util.EnumSet.allOf(Industry.class) : result;
        }
    }

    // --- Users & Accounts ---
    private static final String[] USERS = {"alice", "bob", "charlie", "dave", "eve", "frank", "grace", "henry", "ivan", "julia"};
    private static final String[] ACCOUNT_IDS = {"PL61109010140000071219812874", "DE89370400440532013000", "GB29NWBK60161331926819", "FR7630006000011234567890189", "ES9121000418450200051332", "IT60X0542811101000000123456"};
    private static final String[] CARD_NUMBERS = {"**** **** **** 4532", "**** **** **** 7891", "**** **** **** 3344", "**** **** **** 9102", "**** **** **** 6677", "**** **** **** 2210"};

    // --- Banking ---
    private static final String[] CURRENCIES = {"PLN", "EUR", "USD", "GBP", "CHF", "SEK"};
    private static final String[] TRANSACTION_TYPES = {"transfer", "withdrawal", "deposit", "payment", "refund", "standing-order", "direct-debit"};
    private static final String[] MERCHANT_CATEGORIES = {"grocery", "electronics", "restaurant", "fuel", "subscription", "travel", "pharmacy", "clothing", "insurance", "utilities"};
    private static final String[] FRAUD_REASONS = {"unusual location", "amount exceeds daily limit", "velocity check failed", "blacklisted merchant", "card not present mismatch", "device fingerprint mismatch", "geo-impossible travel"};
    private static final String[] KYC_STATUSES = {"verified", "pending", "expired", "rejected", "under-review"};
    private static final String[] LOAN_TYPES = {"mortgage", "personal", "car", "business", "student"};
    private static final String[] SWIFT_CODES = {"BREXPLPWXXX", "DEUTDEFFXXX", "NWBKGB2LXXX", "BNPAFRPPXXX"};

    // --- Telco ---
    private static final String[] MSISDNS = {"+48501234567", "+48602345678", "+48703456789", "+48804567890", "+48905678901", "+48111222333", "+48444555666", "+48777888999"};
    private static final String[] CELL_IDS = {"LAC:1234/CID:5678", "LAC:2345/CID:6789", "LAC:3456/CID:7890", "LAC:4567/CID:8901", "LAC:5678/CID:9012", "LAC:6789/CID:0123"};
    private static final String[] APN_NAMES = {"internet", "wap", "mms", "ims", "sos", "enterprise.vpn", "iot.m2m"};
    private static final String[] SERVICES = {"VoLTE", "VoWiFi", "5G-SA", "4G-LTE", "RCS", "5G-NSA", "NB-IoT"};
    private static final String[] HANDOVER_TYPES = {"4G-to-5G", "5G-to-4G", "intra-frequency", "inter-frequency", "IRAT", "inter-gNB", "Xn-handover"};
    private static final String[] SMS_TYPES = {"MT", "MO", "flash", "silent", "binary"};
    private static final String[] QOS_CLASSES = {"QCI-1", "QCI-5", "QCI-9", "5QI-1", "5QI-9"};

    // --- E-commerce / AdTech (high volume) ---
    private static final String[] PAGE_TYPES = {"homepage", "product-detail", "search-results", "cart", "checkout", "confirmation", "category", "account"};
    private static final String[] AD_NETWORKS = {"google-ads", "meta-ads", "criteo", "tiktok-ads", "amazon-dsp", "programmatic-rtb"};
    private static final String[] PRODUCT_IDS = {"SKU-001234", "SKU-005678", "SKU-009012", "SKU-003456", "SKU-007890", "SKU-002345"};
    private static final String[] AB_VARIANTS = {"control", "variant-A", "variant-B", "variant-C"};
    private static final String[] SEARCH_QUERIES = {"wireless headphones", "running shoes size 42", "iphone 16 case", "organic coffee beans", "winter jacket", "yoga mat", "bluetooth speaker", "laptop stand"};
    private static final String[] WAREHOUSES = {"WAR-EU-WEST", "WAR-EU-CENTRAL", "WAR-US-EAST", "WAR-APAC"};
    private static final String[] CARRIERS = {"DHL", "UPS", "FedEx", "DPD", "InPost", "GLS"};
    private static final String[] PAYMENT_METHODS = {"credit-card", "debit-card", "blik", "paypal", "apple-pay", "google-pay", "bank-transfer"};
    private static final String[] COUPON_CODES = {"SAVE10", "WELCOME20", "SUMMER15", "FREESHIP", "VIP30", "FLASH50"};

    // --- IoT / Fleet Management (high volume) ---
    private static final String[] DEVICE_IDS = {"DEV-A1B2C3", "DEV-D4E5F6", "DEV-G7H8I9", "DEV-J0K1L2", "DEV-M3N4O5", "DEV-P6Q7R8", "DEV-S9T0U1", "DEV-V2W3X4"};
    private static final String[] SENSOR_TYPES = {"temperature", "humidity", "pressure", "vibration", "gps", "accelerometer", "gyroscope", "fuel-level"};
    private static final String[] VEHICLE_IDS = {"VH-PL-001", "VH-PL-002", "VH-DE-003", "VH-FR-004", "VH-ES-005", "VH-GB-006"};
    private static final String[] GEOFENCE_NAMES = {"warehouse-zone", "customer-site-A", "restricted-area", "parking-lot-B", "delivery-zone-C", "city-center"};
    private static final String[] ALERT_SEVERITIES = {"critical", "major", "minor", "warning", "info"};

    // --- Gaming / Streaming (high volume) ---
    private static final String[] GAME_IDS = {"battle-royale-42", "racing-pro-7", "puzzle-quest-3", "mmo-legends", "card-arena"};
    private static final String[] REGIONS = {"eu-west-1", "eu-central-1", "us-east-1", "us-west-2", "ap-southeast-1", "ap-northeast-1"};
    private static final String[] MATCH_EVENTS = {"kill", "death", "assist", "objective-captured", "item-purchased", "level-up", "achievement"};
    private static final String[] STREAM_QUALITIES = {"4K", "1080p", "720p", "480p", "360p", "audio-only"};
    private static final String[] CDN_POPS = {"CDN-WAW", "CDN-FRA", "CDN-AMS", "CDN-LHR", "CDN-NYC", "CDN-SIN"};

    // --- Infrastructure ---
    private static final String[] ENDPOINTS = {"/api/accounts", "/api/transfers", "/api/cards", "/api/auth/token", "/api/notifications", "/api/sms/send", "/api/cdr/submit", "/api/subscriber/profile", "/api/inventory", "/api/search", "/api/recommendations", "/api/telemetry"};
    private static final String[] MODULES = {"core-banking", "fraud-engine", "payment-gateway", "sms-gateway", "billing", "provisioning", "aaa-server", "charging-system", "recommendation-engine", "inventory-service", "notification-hub", "cdn-origin"};
    private static final String[] DB_OPERATIONS = {"SELECT", "INSERT", "UPDATE", "DELETE", "UPSERT"};
    private static final String[] CACHE_NAMES = {"session-cache", "subscriber-cache", "rate-plan-cache", "balance-cache", "product-cache", "geo-cache", "device-shadow-cache"};
    private static final String[] KAFKA_TOPICS = {"events.transactions", "events.clicks", "events.telemetry", "events.cdr", "events.iot-readings", "events.matchmaking"};
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"};

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(2000);

        var industries = Industry.parse(System.getenv("INDUSTRIES"));
        System.out.println("Active industries: " + industries);

        // Build list of log emitters for selected industries
        var random = new java.util.Random();
        var emitters = new java.util.ArrayList<Runnable>();

        if (industries.contains(Industry.BANKING)) {
            emitters.add(() -> { String u = pick(USERS, random); log.info("User {} authenticated via {}", u, random.nextBoolean() ? "password" : "biometric"); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Session created for user {} from IP {}", u, "10.0." + random.nextInt(255) + "." + random.nextInt(255)); });
            emitters.add(() -> { String u = pick(USERS, random); log.warn("Failed login attempt for user {} from IP {}, attempt {}", u, "10.0." + random.nextInt(255) + "." + random.nextInt(255), random.nextInt(5) + 1); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("MFA challenge sent to user {} via {}", u, random.nextBoolean() ? "SMS" : "authenticator-app"); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Password reset requested for user {} from device {}", u, pick(DEVICE_IDS, random)); });
            emitters.add(() -> { String tx = txId(random); log.info("Transaction {} initiated: {} {} {} from account {}", tx, pick(TRANSACTION_TYPES, random), money(random, 10000), pick(CURRENCIES, random), pick(ACCOUNT_IDS, random)); });
            emitters.add(() -> { String tx = txId(random); log.info("Transaction {} completed successfully in {} ms", tx, random.nextInt(500)); });
            emitters.add(() -> { String tx = txId(random); log.warn("Transaction {} declined: insufficient funds, available balance {} {}", tx, money(random, 1000), pick(CURRENCIES, random)); });
            emitters.add(() -> { String tx = txId(random); log.error("Transaction {} failed: gateway timeout after {} ms", tx, 5000 + random.nextInt(5000)); });
            emitters.add(() -> { String tx = txId(random); log.info("SWIFT transfer {} sent via {} to correspondent bank", tx, pick(SWIFT_CODES, random)); });
            emitters.add(() -> log.info("Interest calculated for account {}: {} {} at {}% APR", pick(ACCOUNT_IDS, random), money(random, 100), pick(CURRENCIES, random), String.format("%.1f", 1 + random.nextDouble() * 8)));
            emitters.add(() -> log.warn("Fraud alert for card {}: {}", pick(CARD_NUMBERS, random), pick(FRAUD_REASONS, random)));
            emitters.add(() -> log.info("Card {} transaction approved at {} for {} {}", pick(CARD_NUMBERS, random), pick(MERCHANT_CATEGORIES, random), money(random, 500), pick(CURRENCIES, random)));
            emitters.add(() -> { String u = pick(USERS, random); log.info("AML check passed for user {}, risk score {}", u, random.nextInt(100)); });
            emitters.add(() -> { String u = pick(USERS, random); log.warn("Suspicious activity detected for user {}: {} transactions in {} minutes totaling {} {}", u, 5 + random.nextInt(20), random.nextInt(10) + 1, money(random, 50000), pick(CURRENCIES, random)); });
            emitters.add(() -> log.error("Card {} blocked: fraud confirmed, last transaction at {} in {}", pick(CARD_NUMBERS, random), pick(MERCHANT_CATEGORIES, random), pick(REGIONS, random)));
            emitters.add(() -> { String u = pick(USERS, random); log.info("KYC status for user {} updated to {}", u, pick(KYC_STATUSES, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.warn("KYC document expired for user {}, last verified {} days ago", u, 30 + random.nextInt(335)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Loan application {} submitted by user {}: {} {} {}, term {} months", "LOAN-" + random.nextInt(100000), u, pick(LOAN_TYPES, random), money(random, 500000), pick(CURRENCIES, random), 12 * (1 + random.nextInt(30))); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Credit score retrieved for user {}: {} from bureau {}", u, 300 + random.nextInt(551), random.nextBoolean() ? "BIK" : "KRD"); });
        }

        if (industries.contains(Industry.TELCO)) {
            emitters.add(() -> log.info("Subscriber {} registered on network, IMSI {}", pick(MSISDNS, random), "260" + (10 + random.nextInt(90)) + String.format("%010d", random.nextLong(10_000_000_000L))));
            emitters.add(() -> log.info("Subscriber {} attached to cell {}", pick(MSISDNS, random), pick(CELL_IDS, random)));
            emitters.add(() -> log.warn("Subscriber {} detached from network, cause: {}", pick(MSISDNS, random), random.nextBoolean() ? "UE-initiated" : "network-initiated"));
            emitters.add(() -> log.info("SIM swap completed for {} to new ICCID {}", pick(MSISDNS, random), "8948" + String.format("%015d", random.nextLong(1_000_000_000_000_000L))));
            emitters.add(() -> log.info("Rate plan changed for {}: {} -> {}", pick(MSISDNS, random), "PLAN-" + random.nextInt(20), "PLAN-" + random.nextInt(20)));
            emitters.add(() -> log.info("Voice call started: {} -> {}, service {}", pick(MSISDNS, random), pick(MSISDNS, random), pick(SERVICES, random)));
            emitters.add(() -> log.info("Voice call ended: {} -> {}, duration {} sec, QoS {}", pick(MSISDNS, random), pick(MSISDNS, random), random.nextInt(3600), pick(QOS_CLASSES, random)));
            emitters.add(() -> log.info("Data session started for {} on APN {}, allocated {} Mbps, bearer {}", pick(MSISDNS, random), pick(APN_NAMES, random), 10 + random.nextInt(990), pick(QOS_CLASSES, random)));
            emitters.add(() -> log.info("Data usage: {} consumed {} MB in session {}", pick(MSISDNS, random), String.format("%.1f", random.nextDouble() * 500), "SES-" + random.nextInt(100000)));
            emitters.add(() -> log.info("SMS {} delivered: {} -> {}, latency {} ms", pick(SMS_TYPES, random), pick(MSISDNS, random), pick(MSISDNS, random), random.nextInt(2000)));
            emitters.add(() -> log.info("Handover {} completed for {} at cell {}", pick(HANDOVER_TYPES, random), pick(MSISDNS, random), pick(CELL_IDS, random)));
            emitters.add(() -> log.warn("Handover failed for {} at cell {}, cause: {}", pick(MSISDNS, random), pick(CELL_IDS, random), random.nextBoolean() ? "target cell congested" : "radio link failure"));
            emitters.add(() -> log.warn("RRC connection dropped for {} at cell {}, RSRP {} dBm", pick(MSISDNS, random), pick(CELL_IDS, random), -140 + random.nextInt(80)));
            emitters.add(() -> log.debug("Cell {} load: {} connected UEs, PRB utilization {}%", pick(CELL_IDS, random), random.nextInt(500), random.nextInt(100)));
            emitters.add(() -> log.info("CDR generated for {}: {} {} charged for {} service", pick(MSISDNS, random), money(random, 50), pick(CURRENCIES, random), pick(SERVICES, random)));
            emitters.add(() -> log.warn("Balance threshold reached for {}: remaining {} {}", pick(MSISDNS, random), money(random, 10), pick(CURRENCIES, random)));
            emitters.add(() -> log.info("Top-up processed for {}: {} {} via {}", pick(MSISDNS, random), String.format("%.2f", 5 + random.nextDouble() * 100), pick(CURRENCIES, random), pick(PAYMENT_METHODS, random)));
            emitters.add(() -> log.info("Roaming session started for {} in network {}, home PLMN {}", pick(MSISDNS, random), "260" + (10 + random.nextInt(90)), "260" + (10 + random.nextInt(90))));
            emitters.add(() -> log.warn("PCRF policy update for {}: downgrade to {} Mbps, reason: fair-usage-exceeded", pick(MSISDNS, random), 1 + random.nextInt(10)));
            emitters.add(() -> log.error("Diameter CCR timeout for {}: charging session {} unresolved after {} ms", pick(MSISDNS, random), "CS-" + random.nextInt(100000), 3000 + random.nextInt(7000)));
        }

        if (industries.contains(Industry.ECOMMERCE)) {
            emitters.add(() -> { String u = pick(USERS, random); log.info("Page view: user {} on {} with variant {}, load time {} ms", u, pick(PAGE_TYPES, random), pick(AB_VARIANTS, random), random.nextInt(3000)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Search query '{}' by user {} returned {} results in {} ms", pick(SEARCH_QUERIES, random), u, random.nextInt(500), random.nextInt(200)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Product {} added to cart by user {}, quantity {}, price {} {}", pick(PRODUCT_IDS, random), u, 1 + random.nextInt(5), money(random, 500), pick(CURRENCIES, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Order {} placed by user {}: {} items, total {} {}, payment via {}", "ORD-" + random.nextInt(1000000), u, 1 + random.nextInt(10), money(random, 2000), pick(CURRENCIES, random), pick(PAYMENT_METHODS, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Coupon {} applied by user {}: discount {} {}", pick(COUPON_CODES, random), u, money(random, 50), pick(CURRENCIES, random)); });
            emitters.add(() -> log.info("Shipment {} dispatched from {} via {}, ETA {} days", "SHP-" + random.nextInt(1000000), pick(WAREHOUSES, random), pick(CARRIERS, random), 1 + random.nextInt(7)));
            emitters.add(() -> log.warn("Inventory low for {}: {} units remaining in {}", pick(PRODUCT_IDS, random), random.nextInt(10), pick(WAREHOUSES, random)));
            emitters.add(() -> log.info("Ad impression served: network {}, campaign {}, bid {} {}, latency {} ms", pick(AD_NETWORKS, random), "CMP-" + random.nextInt(10000), String.format("%.4f", random.nextDouble() * 2), pick(CURRENCIES, random), random.nextInt(50)));
            emitters.add(() -> { String u = pick(USERS, random); log.info("Ad click: user {} on campaign {}, CPC {} {}", u, "CMP-" + random.nextInt(10000), String.format("%.3f", random.nextDouble() * 5), pick(CURRENCIES, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Conversion tracked: user {} completed {} on campaign {}, revenue {} {}", u, random.nextBoolean() ? "purchase" : "signup", "CMP-" + random.nextInt(10000), money(random, 200), pick(CURRENCIES, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.warn("Cart abandoned by user {} after {} minutes, {} items worth {} {}", u, random.nextInt(60), 1 + random.nextInt(8), money(random, 500), pick(CURRENCIES, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Recommendation served to user {}: {} products in {} ms, model version {}", u, 5 + random.nextInt(20), random.nextInt(100), "v" + random.nextInt(10) + "." + random.nextInt(100)); });
        }

        if (industries.contains(Industry.IOT)) {
            emitters.add(() -> log.info("Telemetry received from {}: {} = {}, battery {}%", pick(DEVICE_IDS, random), pick(SENSOR_TYPES, random), String.format("%.2f", -20 + random.nextDouble() * 120), random.nextInt(100)));
            emitters.add(() -> log.warn("Device {} sensor {} reading out of range: {} (threshold: {})", pick(DEVICE_IDS, random), pick(SENSOR_TYPES, random), String.format("%.1f", random.nextDouble() * 200), String.format("%.1f", 50 + random.nextDouble() * 50)));
            emitters.add(() -> log.info("Vehicle {} position update: lat {} lon {}, speed {} km/h, heading {}°", pick(VEHICLE_IDS, random), String.format("%.6f", 50 + random.nextDouble() * 5), String.format("%.6f", 14 + random.nextDouble() * 10), random.nextInt(130), random.nextInt(360)));
            emitters.add(() -> log.info("Vehicle {} entered geofence {}", pick(VEHICLE_IDS, random), pick(GEOFENCE_NAMES, random)));
            emitters.add(() -> log.warn("Vehicle {} fuel level critical: {}%, estimated range {} km", pick(VEHICLE_IDS, random), random.nextInt(10), random.nextInt(50)));
            emitters.add(() -> log.info("OTA update pushed to {} devices in fleet {}, firmware version {}", random.nextInt(1000), "FLEET-" + random.nextInt(50), "fw-" + random.nextInt(10) + "." + random.nextInt(100) + "." + random.nextInt(1000)));
            emitters.add(() -> log.error("Device {} offline for {} minutes, last seen at cell {}", pick(DEVICE_IDS, random), random.nextInt(120), pick(CELL_IDS, random)));
            emitters.add(() -> log.info("Device {} alert: {} severity {} — {}", pick(DEVICE_IDS, random), pick(SENSOR_TYPES, random), pick(ALERT_SEVERITIES, random), random.nextBoolean() ? "threshold exceeded" : "anomaly detected"));
        }

        if (industries.contains(Industry.GAMING)) {
            emitters.add(() -> { String u = pick(USERS, random); log.info("Player {} joined match {} in region {}, latency {} ms", u, pick(GAME_IDS, random), pick(REGIONS, random), random.nextInt(200)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Match event: player {} {} in match {}, score {}", u, pick(MATCH_EVENTS, random), pick(GAME_IDS, random), random.nextInt(10000)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("Stream started: user {} watching content {}, quality {}, CDN {}", u, "VID-" + random.nextInt(100000), pick(STREAM_QUALITIES, random), pick(CDN_POPS, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.warn("Stream buffering for user {}: rebuffer ratio {}%, CDN {}", u, String.format("%.1f", random.nextDouble() * 10), pick(CDN_POPS, random)); });
            emitters.add(() -> { String u = pick(USERS, random); log.info("In-app purchase: user {} bought {} for {} {} in {}", u, "ITEM-" + random.nextInt(5000), money(random, 100), pick(CURRENCIES, random), pick(GAME_IDS, random)); });
            emitters.add(() -> log.info("Matchmaking completed: {} players matched in {} ms, avg rank {}", 2 + random.nextInt(98), random.nextInt(5000), random.nextInt(5000)));
            emitters.add(() -> log.warn("Server {} in region {} at {}% CPU, {} concurrent players", "gs-" + random.nextInt(100), pick(REGIONS, random), 70 + random.nextInt(30), 50 + random.nextInt(950)));
        }

        if (industries.contains(Industry.INFRA)) {
            emitters.add(() -> log.debug("{} on {} completed in {} ms, {} rows affected", pick(DB_OPERATIONS, random), random.nextBoolean() ? "subscribers" : "transactions", random.nextInt(200), random.nextInt(1000)));
            emitters.add(() -> log.error("Database connection pool exhausted for module {}, {} active connections", pick(MODULES, random), 90 + random.nextInt(10)));
            emitters.add(() -> log.debug("Cache {} hit for key {}, latency {} us", pick(CACHE_NAMES, random), "K-" + random.nextInt(10000), random.nextInt(500)));
            emitters.add(() -> log.warn("Cache {} miss rate exceeded threshold: {}%", pick(CACHE_NAMES, random), 20 + random.nextInt(30)));
            emitters.add(() -> log.info("{} {} responded with {} in {} ms", pick(HTTP_METHODS, random), pick(ENDPOINTS, random), 200 + random.nextInt(4) * 100, random.nextInt(500)));
            emitters.add(() -> log.error("{} {} failed with {} after {} ms", pick(HTTP_METHODS, random), pick(ENDPOINTS, random), 500 + random.nextInt(4), 3000 + random.nextInt(7000)));
            emitters.add(() -> log.warn("Circuit breaker opened for module {} after {} consecutive failures", pick(MODULES, random), 3 + random.nextInt(7)));
            emitters.add(() -> log.info("Kafka consumer lag on topic {}: {} messages behind, partition {}", pick(KAFKA_TOPICS, random), random.nextInt(50000), random.nextInt(12)));
        }

        int targetPerSecond = 800;
        long intervalNanos = 1_000_000_000L / targetPerSecond;
        int emitterCount = emitters.size();
        System.out.println("Loaded " + emitterCount + " log templates at " + targetPerSecond + " logs/sec");

        long count = 0;
        long startTime = System.nanoTime();

        while (true) {
            String user = pick(USERS, random);
            String sessionId = "SES-" + Long.toHexString(random.nextLong()).substring(0, 8);

            if (random.nextInt(5) < 2) {
                MDC.put("traceId", java.util.UUID.randomUUID().toString().substring(0, 8));
                MDC.put("userId", user);
                if (random.nextBoolean()) {
                    MDC.put("sessionId", sessionId);
                }
            }

            try {
                emitters.get(random.nextInt(emitterCount)).run();
            } finally {
                MDC.clear();
            }

            count++;
            long expectedTime = startTime + count * intervalNanos;
            while (System.nanoTime() < expectedTime) {
                Thread.onSpinWait();
            }
        }
    }

    private static <T> T pick(T[] array, java.util.Random random) {
        return array[random.nextInt(array.length)];
    }

    private static String txId(java.util.Random random) {
        return "TX-" + (100000 + random.nextInt(900000));
    }

    private static String money(java.util.Random random, double max) {
        return String.format("%.2f", random.nextDouble() * max);
    }
}
