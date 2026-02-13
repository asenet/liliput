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
┌──────────────┐     compact logs      ┌──────┐
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
    <resyncIntervalSeconds>30</resyncIntervalSeconds>
    <connectTimeoutSeconds>5</connectTimeoutSeconds>
    <registrationEnabled>true</registrationEnabled>
    <levels>INFO,WARN,ERROR</levels>
</appender>
```

| Property | Default | Description |
|----------|---------|-------------|
| `registryEndpoint` | `http://localhost:3000/api/plugins/liliput-datasource/resources` | Grafana plugin endpoint for template registration |
| `resyncIntervalSeconds` | `30` | How often to re-register all templates (handles Grafana restarts). Set to `0` to disable. |
| `connectTimeoutSeconds` | `5` | HTTP connect timeout for registration requests |
| `registrationEnabled` | `true` | Set to `false` to output compact logs without registering templates (useful for testing) |
| `levels` | _(empty = all)_ | Comma-separated list of levels to compress. Unmatched levels pass through as plain text. See examples below. |

**`levels` examples:**

```xml
<!-- Compress all levels (default) -->
<levels></levels>
<!-- INFO User alice has logged in  →  I[1,"alice"]   -->
<!-- WARN Error in module payments  →  W[2,"payments"] -->
<!-- DEBUG Connection pool stats    →  D[3]            -->

<!-- Compress only INFO — high-volume logs that benefit most from compression -->
<levels>INFO</levels>
<!-- INFO User alice has logged in  →  I[1,"alice"]              (compressed) -->
<!-- WARN Error in module payments  →  Error in module payments  (plain text) -->
<!-- DEBUG Connection pool stats    →  Connection pool stats     (plain text) -->

<!-- Compress INFO and WARN, keep ERROR and DEBUG readable -->
<levels>INFO,WARN</levels>
<!-- INFO User alice has logged in  →  I[1,"alice"]              (compressed) -->
<!-- WARN Error in module payments  →  W[2,"payments"]           (compressed) -->
<!-- ERROR Fatal: disk full         →  Fatal: disk full          (plain text) -->

<!-- Compress everything except DEBUG — keep debug logs human-readable for development -->
<levels>INFO,WARN,ERROR,TRACE</levels>
<!-- DEBUG Connection pool stats    →  Connection pool stats     (plain text) -->
<!-- everything else                →  compressed                             -->
```

Properties can use environment variables and defaults:

```xml
<registryEndpoint>${REGISTRY_ENDPOINT:-http://localhost:3000/api/plugins/liliput-datasource/resources}</registryEndpoint>
```

Spring Boot users can bridge from `application.yml` using `logback-spring.xml`:

```xml
<configuration>
    <springProperty name="endpoint" source="liliput.registry-endpoint"
                    defaultValue="http://localhost:3000/api/plugins/liliput-datasource/resources"/>
    <springProperty name="resync" source="liliput.resync-interval-seconds" defaultValue="30"/>

    <appender name="LILIPUT" class="pl.asenet.liliput.LiliputAppender">
        <registryEndpoint>${endpoint}</registryEndpoint>
        <resyncIntervalSeconds>${resync}</resyncIntervalSeconds>
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
  resync-interval-seconds: 60
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
<level_char>[<template_id>, <param1>, <param2>, ...]
```

| Level char | Severity |
|-----------|----------|
| `T` | trace |
| `D` | debug |
| `I` | info |
| `W` | warning |
| `E` | error |

Example: `W[3,"payments"]` = WARNING, template #3, param "payments"

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

## Grafana plugin settings

In the datasource configuration, set **Loki URL** to point to your Loki instance (default: `http://loki:3100`).

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

## Requirements

- Docker and Docker Compose
- Docker Loki logging driver (`docker plugin install grafana/loki-docker-driver:latest --alias loki --grant-all-permissions`)
