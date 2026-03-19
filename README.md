# Liliput

Template-based log compression for Java applications. Reduces log storage and bandwidth by up to 70-85% while preserving full readability in Grafana.

<p align="center">
  <img src="liliput-logo.png" alt="Liliput" width="300">
</p>

## How it works

Instead of storing full log messages:

```
INFO Order 847 processed             (24 bytes)
INFO User bob has logged in          (29 bytes)
```

Liliput sends compact payloads:

```
I[1,"alice"]    (12 bytes)
I[2,847]        (8 bytes)
I[1,"bob"]      (10 bytes)
```

Templates (`User {} has logged in`, `Order {} processed`) are registered once with the Grafana plugin, which rehydrates the logs on read.

## Architecture

```
┌──────────────┐     compact logs       ┌──────┐
│  Java App    │ ───────────────────>   │ Loki │
│  + Liliput   │    I[1,"alice"]        └──┬───┘
│  Appender    │                           │
│              │──── register template ──> │
└──────────────┘    {tid:1, tpl:          ┌┴────────────────┐
                     "User {} logged in"} │ Grafana         │
                                          │  + Liliput      │
                                          │    Plugin       │
                                          │                 │
                                          │ rehydrates:     │
                                          │ I[1,"alice"] -> │
                                          │ "User alice     │
                                          │  has logged in" │
                                          └─────────────────┘
```

**Components:**

- **LiliputAppender** (Java) - Logback appender that intercepts SLF4J logs, assigns template IDs, and outputs compact `<level><json_array>` to stdout
- **TemplateRegistry** (Java) - Thread-safe bidirectional map of templates to numeric IDs
- **Grafana Plugin** (Go + TypeScript) - Backend datasource that queries Loki, rehydrates compact logs, and serves them to Grafana

## Quick start

```bash
cd example
docker compose up -d --build
```

Open [http://localhost:3000](http://localhost:3000) and go to the **Liliput Demo** dashboard.

The dashboard has:
- **Stats row** - compact size vs full size, savings %, log line count
- **Raw logs** - compact payloads as stored in Loki
- **Rehydrated logs** - full human-readable logs decoded by Liliput
- **Search** (textbox) - full-text search on rehydrated logs
- **Severity** (dropdown) - filter by log level (info, warning, error, debug, trace)

## Integration with your Java app

### 1. Add the dependency

```groovy
// build.gradle
dependencies {
    implementation project(':liliput4j')
    // or, when published to a Maven repository:
    // implementation 'pl.liliput:liliput4j:1.0-SNAPSHOT'
}
```

The appender transitively brings in Logback and Gson.

### 2. Configure logback.xml

```xml
<configuration>
    <appender name="LILIPUT" class="pl.asenet.liliput.LiliputAppender">
        <registryEndpoint>http://localhost:3000/api/plugins/liliput-datasource/resources</registryEndpoint>
    </appender>
    <root level="DEBUG">
        <appender-ref ref="LILIPUT" />
    </root>
</configuration>
```

All available properties:

```xml
<appender name="LILIPUT" class="pl.asenet.liliput.LiliputAppender">
    <registryEndpoint>http://localhost:3000/api/plugins/liliput-datasource/resources</registryEndpoint>
    <connectTimeoutSeconds>5</connectTimeoutSeconds>
    <registrationEnabled>true</registrationEnabled>
    <levels>INFO,WARN,ERROR</levels>
    <circuitBreakerThreshold>3</circuitBreakerThreshold>
</appender>
```

| Property | Default | Description |
|----------|---------|-------------|
| `registryEndpoint` | `http://localhost:3000/api/plugins/liliput-datasource/resources` | Grafana plugin endpoint for template registration |
| `connectTimeoutSeconds` | `5` | HTTP connect timeout for registration requests |
| `registrationEnabled` | `true` | Set to `false` to output compact logs without registering templates (useful for testing) |
| `levels` | _(empty = all)_ | Comma-separated list of levels to compress. Unmatched levels pass through as plain text. |
| `circuitBreakerThreshold` | `3` | Number of consecutive HTTP failures before switching to fallback mode |

Properties can use environment variables and defaults:

```xml
<registryEndpoint>${REGISTRY_ENDPOINT:-http://localhost:3000/api/plugins/liliput-datasource/resources}</registryEndpoint>
```

Spring Boot users can bridge from `application.yml` using `logback-spring.xml`:

```xml
<configuration>
    <springProperty name="endpoint" source="liliput.registry-endpoint"
                    defaultValue="http://localhost:3000/api/plugins/liliput-datasource/resources"/>

    <appender name="LILIPUT" class="pl.asenet.liliput.LiliputAppender">
        <registryEndpoint>${endpoint}</registryEndpoint>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="LILIPUT" />
    </root>
</configuration>
```

```yaml
# application.yml
liliput:
  registry-endpoint: http://grafana:3000/api/plugins/liliput-datasource/resources
```

### 3. Log normally with SLF4J

```java
private static final Logger log = LoggerFactory.getLogger(MyService.class);

log.info("User {} has logged in", username);
log.warn("Error in module {}", module);
log.info("Order {} processed", orderId);
```

No code changes needed. The appender intercepts parameterized messages, extracts templates, and outputs compact payloads automatically.

## Compact format

Each log line is encoded as:

```
<level_char>[<template_id>, <param1>, <param2>, ..., <mdc_map>?, <stack_trace>?]
```

| Level char | Severity |
|-----------|----------|
| `T` | trace |
| `D` | debug |
| `I` | info |
| `W` | warning |
| `E` | error |

Examples:

```
I[1,"alice"]                                                   # simple message
I[3,"alice","purchase",4821]                                   # multiple params
W[2,"payments"]                                                # warning level
I[1,"alice",{"traceId":"abc-123","userId":"alice"}]            # with MDC context
E[5,"alice",{"traceId":"abc-123"},"java.lang.RuntimeExcep..."] # MDC + exception
E[5,"alice","java.lang.RuntimeException: Something broke\n..."] # exception only
```

## Reliability features

### Circuit breaker / fallback mode

If the Grafana plugin endpoint becomes unreachable (e.g. Grafana restart, network issue), the appender automatically switches to writing full, standard-format log lines to **prevent data loss**:

```
WARN Error in module payments MDC={traceId=abc-123}
ERROR Processing failed for user alice
java.lang.RuntimeException: Connection refused
    at com.example.Service.process(Service.java:42)
```

The circuit breaker opens after a configurable number of consecutive HTTP failures (default: 3). It closes automatically when a subsequent template registration succeeds.

### Efficient template registration

Templates are registered with the Grafana plugin via async HTTP POST only when a **new** template is first seen. A local cache tracks which template IDs have been successfully acknowledged by the server (HTTP 2xx response). Templates already in this cache are never re-registered, eliminating redundant network calls. This replaces the previous periodic resync approach.

### MDC support

If the SLF4J MDC (Mapped Diagnostic Context) contains data at log time, the MDC map is included in the compact payload as a JSON object after the template parameters:

```java
MDC.put("traceId", "abc-123");
MDC.put("userId", "alice");
log.info("User {} has logged in", "alice");
// Output: I[1,"alice",{"traceId":"abc-123","userId":"alice"}]
```

When the MDC is empty, it is omitted from the payload. In fallback mode, MDC is rendered as `MDC={traceId=abc-123, userId=alice}`.

### Exception handling

If a log event contains a `Throwable`, the full stack trace is appended as a raw string at the end of the compact array:

```java
try {
    // ...
} catch (Exception e) {
    log.error("Processing failed for user {}", "alice", e);
}
// Output: E[5,"alice","java.lang.RuntimeException: Something broke\n\tat ..."]
```

Stack traces cannot be templated, so they are always included verbatim. MDC and exceptions can appear together in the same payload.

### Level filtering

The `levels` property accepts a comma-separated list of log levels to compress. Levels not in the list pass through as plain formatted text. When empty (default), all levels are compressed.

```xml
<!-- Compress all levels (default) -->
<levels></levels>
<!-- INFO User alice has logged in  →  I[1,"alice"]   -->
<!-- WARN Error in module payments  →  W[2,"payments"] -->

<!-- Compress only INFO — high-volume logs that benefit most from compression -->
<levels>INFO</levels>
<!-- INFO User alice has logged in  →  I[1,"alice"]              (compressed) -->
<!-- WARN Error in module payments  →  Error in module payments  (plain text) -->

<!-- Compress INFO and WARN, keep ERROR and DEBUG readable -->
<levels>INFO,WARN</levels>
```

## Grafana plugin

### Backend (Go)

#### Persistent template store

Template mappings are persisted to a JSON file on disk so they survive plugin restarts and Grafana upgrades. The registry is loaded from disk on plugin startup and saved after each new template registration.

Storage path resolution (in order):
1. `LILIPUT_REGISTRY_PATH` environment variable
2. `~/.config/liliput/templates.json` (or platform equivalent via `os.UserConfigDir()`)
3. Falls back to temp directory if config dir is unavailable

#### Rehydration

When Grafana queries for logs, the plugin:
1. Fetches compact log lines from Loki via `query_range`
2. Parses each line's level prefix and JSON array
3. Looks up the template ID in the registry
4. Replaces `{}` placeholders with the stored parameters
5. Separates MDC objects and stack traces from template parameters, appending them as extra context

If a template ID is not found (e.g. registry was cleared, new app instance), the plugin returns a placeholder instead of failing:

```
[UNKNOWN TEMPLATE #42] alice orders
```

This ensures logs are never silently dropped, even when template mappings are incomplete.

#### Server-side search and filtering

Since Loki stores compact log lines, standard LogQL regex searches won't match full-text content like `"alice logged in"`. The plugin solves this by performing search filtering **server-side** in Go:

1. Fetch raw compact lines from Loki
2. Rehydrate each line into full text
3. Apply the search filter on the rehydrated body (case-insensitive)
4. Apply severity filter
5. Return only matching results to the Grafana frontend

This saves browser memory and CPU by filtering on the backend before results reach the UI.

#### Compression stats

The plugin supports a `stats` query type that calculates compression savings by comparing compact payload sizes against rehydrated full-text sizes, returning `compact_bytes`, `full_bytes`, `savings_percent`, and `line_count`.

#### API endpoints

| Method | Path          | Description                        |
|--------|---------------|------------------------------------|
| POST   | `/register`   | Register a template (from liliput4j) |
| GET    | `/templates`  | List all registered templates (JSON) |

### Frontend (TypeScript)

The query editor provides two input fields:

- **LogQL**: The Loki stream selector (e.g. `{compose_service="demo"}`)
- **Search**: Full-text search applied server-side after rehydration

Both fields support Grafana template variables. The datasource configuration has a single **Loki URL** field (default: `http://loki:3100`).

## Searching compact logs

### Full-text search (in Grafana)

Use the **Search** field on the dashboard. The plugin rehydrates all logs first, then filters by your search term. Case-insensitive.

### Native LogQL filters (in Loki)

These work directly on the compact payloads stored in Loki:

```logql
# Filter by severity (only warnings and errors)
{compose_service="myapp"} |~ "^[WE]"

# Filter by template ID
{compose_service="myapp"} |= "[1,"

# Filter by parameter value
{compose_service="myapp"} |= "\"alice\""
```

## Project structure

```
liliput/
├── liliput4j/                # Java library (publishable JAR)
│   ├── build.gradle
│   └── src/
│       ├── main/java/pl/asenet/liliput/
│       │   ├── LiliputAppender.java     # Logback appender
│       │   └── TemplateRegistry.java    # Template ID registry
│       └── test/java/pl/asenet/liliput/
│           ├── LiliputAppenderTest.java
│           └── TemplateRegistryTest.java
├── liliput-grafana-plugin/          # Grafana datasource plugin
│   ├── Dockerfile
│   ├── go.mod
│   ├── package.json
│   ├── pkg/plugin/
│   │   ├── datasource.go           # Go backend (Loki proxy + rehydration)
│   │   └── datasource_test.go
│   └── src/
│       ├── plugin.json
│       ├── QueryEditor.tsx
│       ├── ConfigEditor.tsx
│       ├── datasource.ts
│       └── types.ts
├── example/                         # Demo (docker compose)
│   ├── docker-compose.yaml
│   ├── Dockerfile
│   ├── build.gradle
│   ├── loki-config.yaml
│   ├── src/main/java/pl/asenet/Main.java
│   └── grafana-provisioning/
│       ├── datasources/liliput.yaml
│       └── dashboards/json/liliput-demo.json
├── build.gradle                     # Root Gradle config
├── settings.gradle                  # include 'liliput4j', 'example'
└── README.md
```

## Running tests

### Java (liliput4j)

```bash
./gradlew :liliput4j:test
```

Covers:
- Compact JSON output format and template ID consistency
- Template registration via HTTP and deduplication
- Level filtering (compress only specified levels, pass others as plain text)
- Circuit breaker: opens after consecutive failures, falls back to raw logs
- Circuit breaker: recovers automatically when endpoint becomes reachable
- Efficient registration: cached template IDs are not re-registered
- MDC: included in compact payload when present, omitted when empty
- MDC: included in fallback output
- Exceptions: stack trace appended to compact payload
- Exceptions: combined with MDC in same payload
- Exceptions: included in fallback output

### Go (Grafana plugin)

```bash
cd liliput-grafana-plugin && go test ./pkg/plugin/ -v
```

Covers:
- `Rehydrate()` with single, multiple, and no placeholders
- `RehydrateLine()` end-to-end compact string rehydration (e.g. `I[1,"alice"]` -> `User alice has logged in`)
- Unknown template returns `[UNKNOWN TEMPLATE #id]` placeholder
- MDC objects handled correctly in compact lines
- All level prefix mappings (I/W/E/D/T)
- Non-compact and short lines pass through unchanged
- Persistent store: save to file, clear, reload, verify templates restored
- Missing registry file does not cause errors
- Server-side search: rehydrate then filter, case-insensitive, empty search matches all

## Requirements

- Docker and Docker Compose
- Docker Loki logging driver (`docker plugin install grafana/loki-docker-driver:latest --alias loki --grant-all-permissions`)

### For local development

- Java 21+ and Gradle 8.4+ (for liliput4j)
- Go 1.21+ (for the Grafana plugin backend)
- Node.js 18+ (for the Grafana plugin frontend)

## Tech stack

| Component | Technology |
|-----------|-----------|
| Java Library | Java 21, Logback 1.5, Gson 2.11, JUnit 5 |
| Grafana Plugin (backend) | Go 1.21, Grafana Plugin SDK |
| Grafana Plugin (frontend) | TypeScript 5, React 18, Grafana Data/Runtime 10 |
| Log Storage | Grafana Loki 2.9 |
| Visualization | Grafana 10.4 |
| Build | Gradle 8.4, Webpack 5, Docker multi-stage |
