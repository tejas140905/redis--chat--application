# Freightfox Chat — Spring Boot + Redis

A small chat service built with Spring Boot 3.2, Java 21 and Redis. Rooms, participants
and message history live in Redis, and messages are pushed to connected browsers in real
time over Server-Sent Events (SSE) fed by Redis Pub/Sub.

```
Browser (EventSource) ──GET /stream──► ChatController ──► RealtimeService (SseEmitter registry)
                                                   ▲
POST /messages ──► ChatRoomService ──RPUSH──► Redis List
                        └──PUBLISH──► chat:room:{id}:messages ──► ChatMessageListener ──┘
```

## Features

- Create, join and delete chat rooms (duplicate/unknown rooms are rejected with 409/404).
- Send messages; history is stored in a Redis List trimmed to `app.chat.max-history-limit`.
- Message history newest-N query, returned oldest-first with ISO-8601 UTC timestamps.
- Real-time delivery: REST send → Redis Pub/Sub → listener → SSE clients of that room only.
- SSE heartbeat comments every 15 s, automatic emitter cleanup on completion/timeout/error.
- Bean validation on every request field, consistent JSON error envelope.
- Swagger/OpenAPI documentation and an **optional** demo UI at `/` (static HTML, not required to use the API).
- Responsive demo UI: desktop sidebar, mobile/tablet drawer, safe-area aware, touch-sized controls.
- Redis AOF persistence in local Docker runs, so messages survive restarts (Render's free Key Value is ephemeral — see *Free-tier caveats*).
- 62 automated tests (unit + MockMvc + real-Redis integration + end-to-end SSE).

### The demo UI is optional

Nothing in the service depends on `static/index.html`. All four required endpoints — and the
SSE stream — are plain HTTP and can be exercised with Postman, curl or Swagger UI.
The UI only demonstrates live delivery in a browser. You can delete `static/index.html`
and the API behaves identically.


## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.2.2 (Web MVC, Validation, Actuator) |
| Redis access | Spring Data Redis (Lettuce), `RedisTemplate<String, Object>` |
| Serialization | Jackson, `GenericJackson2JsonRedisSerializer` with restricted default typing |
| Real-time | Server-Sent Events (`SseEmitter`) fed by Redis Pub/Sub |
| Docs | springdoc-openapi 2.3.0 (Swagger UI) |
| Build | Maven 3.9 |
| Tests | JUnit 5, Mockito, MockMvc, Testcontainers (Redis 7) |

## Prerequisites

- Java 21 and Maven 3.9+ (for local builds), **or** Docker Desktop only (Docker builds the app).
- Redis 7 reachable at `localhost:6379` for local runs, or the Redis container described below.
- Ports `8080` (app) and `6379` (Redis) free.

## Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: ${PORT:8080}       # Render and other PaaS platforms inject PORT
  address: 0.0.0.0         # reachable from outside the container

spring:
  data:
    redis:
      host: localhost      # overridden by SPRING_DATA_REDIS_URL / _HOST in containers
      port: 6379
      timeout: 2000ms

app:
  chat:
    max-history-limit: 100      # messages kept per room (list trimming)
    default-history-limit: 50   # used when ?limit is omitted
    sse-timeout-ms: 1800000     # 30 min per SSE connection
    sse-heartbeat-ms: 15000     # keep-alive comment interval

springdoc:
  api-docs:   { path: /api-docs }
  swagger-ui: { path: /swagger-ui.html }
```

Every value can be overridden with an environment variable, e.g.
`APP_CHAT_MAX_HISTORY_LIMIT=500` or `SPRING_DATA_REDIS_HOST=redis`.

`application-prod.yml` is the hardened profile used in production
(`SPRING_PROFILES_ACTIVE=prod`): only the `health` and `info` Actuator endpoints are
exposed, health details are hidden, and application/Redis logging is set to `INFO`.
Render activates this profile in `render.yaml`; local runs keep the default profile.

## Run with Docker (recommended)

```bash
docker network create freightfox
docker run -d --name freightfox-redis --network freightfox \
  -v freightfox-redis-data:/data redis:7-alpine redis-server --appendonly yes

docker build -t freightfox-chat .
docker run -d --name freightfox-chat --network freightfox -p 8080:8080 \
  -e SPRING_DATA_REDIS_HOST=freightfox-redis freightfox-chat

docker logs -f freightfox-chat       # app logs
```

The app is served at <http://localhost:8080>. Redis data is written to the
`freightfox-redis-data` volume with AOF enabled, so chat history survives restarts.

Stop and clean up:

```bash
docker rm -f freightfox-chat freightfox-redis
docker volume rm freightfox-redis-data   # also deletes chat history
```

## Run locally without Docker for the app

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine redis-server --appendonly yes
mvn clean package -DskipTests
java -jar target/chat-app-1.0.0.jar
```

## REST API

Base path: `/api/chatapp/chatrooms`

| Method | Path | Description | Success |
|---|---|---|---|
| POST | `/` | Create a room | 200 |
| POST | `/{roomId}/join` | Join a room | 200 |
| POST | `/{roomId}/messages` | Send a message | 200 |
| GET | `/{roomId}/messages?limit=N` | Latest N messages, oldest first | 200 |
| GET | `/{roomId}/stream` | SSE stream for the room | 200 `text/event-stream` |
| DELETE | `/{roomId}` | Delete room, participants and history | 200 |

`roomId` is the room name. It must start with a letter or digit and may contain letters,
digits, spaces, `.`, `-` and `_` (max 64 chars). `limit` must be 1–100.

### Create a room

```bash
curl -X POST http://localhost:8080/api/chatapp/chatrooms \
  -H "Content-Type: application/json" \
  -d '{"roomName": "general"}'
```

```json
{"message":"Chat room 'general' created successfully.","roomId":"general","status":"success"}
```

Duplicate → `409 Conflict`:
```json
{"message":"Chat room 'general' already exists","status":"error","data":null}
```

### Join a room

```bash
curl -X POST http://localhost:8080/api/chatapp/chatrooms/general/join \
  -H "Content-Type: application/json" \
  -d '{"participant": "alice"}'
```

```json
{"message":"User 'alice' joined chat room 'general'.","status":"success","data":null}
```

Unknown room → `404 Not Found`:
```json
{"message":"Chat room 'nope' not found","status":"error","data":null}
```

### Send a message

```bash
curl -X POST http://localhost:8080/api/chatapp/chatrooms/general/messages \
  -H "Content-Type: application/json" \
  -d '{"participant": "alice", "message": "Hello, everyone!"}'
```

```json
{"message":"Message sent successfully.","status":"success","data":null}
```

Sending is also treated as joining, so the participant Set stays accurate even if the client
skips `/join`. The message is persisted *before* it is published, so a subscriber that
immediately reads history always sees it.

### Get history

```bash
curl "http://localhost:8080/api/chatapp/chatrooms/general/messages?limit=2"
```

```json
{
  "messages": [
    {"participant":"alice","message":"first","timestamp":"2026-10-21T14:32:58.285882673Z"},
    {"participant":"bob","message":"second","timestamp":"2026-10-21T14:32:58.402815936Z"}
  ]
}
```

- `limit=1` returns only the newest message.
- Results are always chronological (oldest first).
- `limit` defaults to `app.chat.default-history-limit` (50).
- `limit` of `0`, a negative number, `>100` or a non-number → `400`.
- Empty room → `{"messages": []}`; unknown room → `404`.

### Delete a room

```bash
curl -X DELETE http://localhost:8080/api/chatapp/chatrooms/general
```

Removes the metadata hash, participants set and message list in one `DEL`. Unknown room → `404`.

### Real-time subscription (SSE)

**Endpoint:** `GET /api/chatapp/chatrooms/{roomId}/stream` — optional; REST clients that only
need send/history can ignore it entirely.

```bash
curl -N -H "Accept: text/event-stream" \
  http://localhost:8080/api/chatapp/chatrooms/general/stream
```

Frame sequence:

```
event:connected
retry:3000
data:{"roomId":"general"}

:keep-alive

id:7dc0a766-4ec7-43ce-b608-82087cab2158
event:message
data:{"participant":"alice","message":"Hi!","timestamp":"2026-10-21T14:33:20.544412568Z"}
```

In a browser:

```js
const es = new EventSource("/api/chatapp/chatrooms/general/stream");
es.addEventListener("message", (e) => console.log(JSON.parse(e.data)));
es.addEventListener("connected", () => console.log("live"));
```

How a REST message reaches a connected client:

1. `POST /api/chatapp/chatrooms/{roomId}/messages` validates the body and calls
   `ChatRoomService.sendMessage`.
2. The service appends the message to the Redis List `chat:room:{roomId}:messages`
   (`RPUSH`, then `LTRIM`) — persistence happens first.
3. It publishes the same `ChatMessage` JSON on the Redis Pub/Sub channel
   `chat:room:{roomId}:messages` (`PUBLISH`).
4. The application's single `RedisMessageListenerContainer` — subscribed to
   `chat:room:*:messages` — invokes `ChatMessageListener`, which reads the room id from the
   channel name and calls `RealtimeService.broadcast(roomId, message)`.
5. `RealtimeService` keeps an `SseEmitter` per connected client grouped by room id, and writes
   one `event: message` frame to every emitter registered for that room, and none for other
   rooms.

The HTTP request that sends a message never writes to an emitter itself: the only producer
for SSE is the Redis listener, so each subscriber receives the message once and only through
Pub/Sub.

Behaviour:

- Only clients subscribed to the *same* room receive a message.
- Delivery path is single: the sender never writes to clients directly; only
  `ChatMessageListener` (fed by Redis Pub/Sub) calls the SSE registry, so no duplicates.
- `:keep-alive` comments every 15 s keep proxies from closing idle connections.
- Connections close on server shutdown; dead clients are removed on the next failed write.
- Unknown room → `404` (delivered as an `event: error` frame because the client asked for
  `text/event-stream`).

### Error envelope

```json
{"message":"...","status":"error","data":null}
```

| Status | When |
|---|---|
| 400 | validation failure, malformed JSON, invalid `limit`, invalid room id |
| 404 | unknown room / unknown resource |
| 405 | unsupported HTTP method |
| 409 | duplicate room |
| 415 | wrong `Content-Type` |
| 500 | unexpected server error |

## Redis data structures

All keys are built in one place: `com.freightfox.chatapp.redis.RedisKeys`.

| Key / channel | Type | Contents |
|---|---|---|
| `chat:room:{id}:metadata` | Hash | `name`, `createdAt` (ISO instant) |
| `chat:room:{id}:participants` | Set | participant names |
| `chat:room:{id}:messages` | List | `ChatMessage` JSON, append at tail, trimmed to max |
| `chat:room:{id}:messages` | Pub/Sub channel | published `ChatMessage` for real-time |

Inspect from a shell:

```bash
docker exec -it freightfox-redis redis-cli HGETALL chat:room:general:metadata
docker exec -it freightfox-redis redis-cli SMEMBERS chat:room:general:participants
docker exec -it freightfox-redis redis-cli LRANGE chat:room:general:messages 0 -1
docker exec -it freightfox-redis redis-cli SUBSCRIBE chat:room:general:messages
```

## Tests

```bash
mvn test
```

62 tests, all executed and passing:

| Class | Kind | Covers |
|---|---|---|
| `ChatRoomServiceTest` (14) | Spring + real Redis | create, duplicate, concurrent create, join/leave, send + persistence, ordering, limit clamping, trimming, deletion, key cleanup |
| `ChatControllerTest` (22) | `@WebMvcTest` + Mockito | status codes, validation, malformed JSON, limit bounds, SSE endpoint wiring |
| `ChatIntegrationTest` (12) | HTTP + real Redis | all endpoints, 409/404/400, history ordering, ISO timestamps, SSE delivery and room isolation |
| `RealtimeServiceTest` (8) | unit | per-room fan-out, isolation, dead-subscriber cleanup, heartbeats |
| `ChatMessageListenerTest` (4) | unit | channel → room mapping, wrong payload, deserialization failure |
| `PubSubServiceTest` (1) | unit | publishes to `chat:room:{id}:messages` |
| `ChatApplicationTests` (1) | Spring context | context loads with Redis |

Integration tests normally start their own Redis through Testcontainers:

```bash
# requires a Docker daemon visible to Testcontainers
mvn test
```

If Testcontainers cannot use the Docker socket (for example a Docker Desktop build whose
socket proxy rejects older API versions), point the tests at an already running Redis
instead — no mocks, still a real server:

```bash
docker run -d --rm --name freightfox-test-redis -p 6379:6379 \
  redis:7-alpine redis-server --save "" --appendonly no

# Git Bash / macOS / Linux
TEST_REDIS_HOST=localhost mvn -B test

# Windows PowerShell
$env:TEST_REDIS_HOST="localhost"; mvn -B test
```

The suite flushes only the Redis it is given (the dedicated container started above),
so it never touches the application's data.

## API documentation

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/api-docs>
- Demo UI: <http://localhost:8080/> (SSE-based, no polling)

## Health check

```bash
curl http://localhost:8080/actuator/health
```

```json
{"status":"UP","components":{"redis":{"status":"UP","details":{"version":"7.4.11"}}}}
```

## Deploy to Render (public URL)

The repository ships a [`render.yaml`](render.yaml) Blueprint that provisions the API
together with a Redis-compatible Render Key Value instance.

1. Render Dashboard → **New +** → **Blueprint**.
2. Select this repository. Render reads `render.yaml` and shows both resources.
3. Click **Apply** and wait for the first build (~4–5 minutes; Maven downloads its
   dependencies inside the Docker build).
4. The API is then live at `https://<service-name>.onrender.com`.

What the Blueprint sets up:

| Setting | Value |
| --- | --- |
| Web service | `runtime: docker`, built from `./Dockerfile`, `plan: free` |
| Health check | `GET /actuator/health` |
| Redis wiring | `SPRING_DATA_REDIS_URL` ← the Key Value instance's private `connectionString` |
| Production profile | `SPRING_PROFILES_ACTIVE=prod` → `application-prod.yml` |
| Actuator | `health,info` only, and health details suppressed |
| Logging | `INFO` for the app and for Spring Data Redis |

`application.yml` reads `${PORT:8080}` and binds `0.0.0.0`, so the app accepts whatever
port the platform assigns (Render's default is `10000`) while still running on `8080`
locally.

A public URL is created in your Render account by the steps above; this repository does
not claim that a deployment has already been performed. Verify your own deployment:

```bash
curl https://<service-name>.onrender.com/actuator/health
# {"status":"UP"}   <- no component details: that is the hardening working
```

### Free-tier caveats

- **The service sleeps after 15 minutes** without traffic, and the next request takes
  roughly 30–60 seconds to wake it. Open the URL once before a demo.
- **The Key Value instance is 25 MB and does not persist data.** Chat rooms and history
  are lost on restart or redeploy. Acceptable for a demo, not for real use.
- **750 instance-hours per workspace per month.** A slept service does not consume them.
- `region: oregon` is set on **both** resources and they must match, or the private
  network lookup fails. Change both together (e.g. to `singapore`) for lower latency
  from India.
- A single instance is used. Horizontal scaling would break SSE fan-out without sticky
  sessions — see *Design decisions and limitations*.

## Project layout

```
.
├── .dockerignore                 build-context exclusions
├── .gitattributes                line-ending normalization
├── .gitignore                    build output / secret exclusions
├── Dockerfile                    multi-stage Maven → JRE image
├── render.yaml                   Render Blueprint (web + Key Value)
├── pom.xml
├── README.md
└── src
    ├── main/java/com/freightfox/chatapp
    │   ├── config/       RedisConfig, PubSubConfig, OpenApiConfig
    │   ├── controller/   ChatController (REST + SSE)
    │   ├── exception/    GlobalExceptionHandler, RoomNotFoundException, RoomAlreadyExistsException
    │   ├── listener/     ChatMessageListener (Redis Pub/Sub → SSE)
    │   ├── model/        ChatMessage, request/response DTOs, ValidationRules
    │   ├── redis/        RedisKeys (key/channel naming)
    │   ├── service/      ChatRoomService, PubSubService, RealtimeService
    │   └── ChatApplication.java
    ├── main/resources
    │   ├── application.yml
    │   ├── application-prod.yml  public-surface hardening
    │   └── static/index.html     responsive demo UI
    └── test/java/com/freightfox/chatapp
        ├── controller/ integration/ listener/ service/ support/
        └── ChatApplicationTests.java
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| App container restarts, logs `Connection refused` | Redis is not up yet; `docker logs freightfox-redis`, then `docker logs freightfox-chat` |
| `GET /actuator/health` shows `redis: DOWN` | Wrong host/port; inside the Docker network use `SPRING_DATA_REDIS_HOST=freightfox-redis` |
| SSE connects but no messages arrive | Check the room id; publish a message and watch `docker logs -f freightfox-chat` for `Delivered message ... to room ...` |
| Messages disappear after a restart | AOF disabled or the volume was removed (`docker volume rm freightfox-redis-data`); check `docker exec freightfox-redis ls /data/appendonlydir` |
| `curl` sends invalid JSON on Windows PowerShell | PowerShell strips inner quotes for native commands; use a JSON file or `Invoke-RestMethod` |
| `mvn test` fails to find Docker | Use the `TEST_REDIS_HOST` variant shown above |
| Swagger UI empty | Open `/swagger-ui.html` (the JSON is at `/api-docs`) |

## Design decisions and limitations

These are deliberate scope decisions for this assignment, not defects. They are listed here
so reviewers can judge them explicitly.

1. **No authentication or authorization.** Any caller that can reach the API can create or
   delete rooms and post under any participant name; `participant` comes from the request
   body. The assignment did not ask for auth. A production build would add Spring Security,
   derive identity from the authenticated principal instead of the request body, and add rate
   limiting and TLS termination.
2. **The SSE registry is per application instance.** `RealtimeService` stores emitters in
   memory, so an instance writes only to the clients connected to that instance. Redis Pub/Sub
   is still shared: every instance receives every published message. The single-instance
   deployment used here is unaffected. Horizontal scaling would need sticky
   sessions or a shared relay (e.g. STOMP with a Redis-backed broker); alternatively clients
   reconnect and resynchronise history through the REST endpoint.
3. **Posting implicitly joins the sender.** `sendMessage` runs `SADD` on the room's
   participants Set before storing the message. This keeps membership accurate without
   returning `403` to clients that send before calling `/join`, and preserves the required API
   flow. Explicit `POST /join` is still supported. The consequence is that `participant` is a
   self-declared presence marker, not an authorization decision.
4. **No room or participant listing endpoint.** `SMEMBERS` / `KEYS` remain available through
   `redis-cli`; listing was not required by the assignment.
5. **`allkeys-lru` with 256 MB** may evict data under memory pressure. The policy is set for
   the Render Key Value instance in `render.yaml`; acceptable for this assignment.

Jackson default typing for Redis payloads is restricted to `com.freightfox.chatapp.model.*`
plus JDK collection/time types to avoid unsafe polymorphic deserialization.
