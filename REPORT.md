# Freightfox Chat Application — Final Delivery Report

**Project:** `com.freightfox:chat-app:1.0.0`
**Stack:** Java 21 · Spring Boot 3.2.2 · Spring Data Redis (Lettuce) · Redis 7.4 · Docker Compose
**Date:** 21 October 2026
**Status:** Complete — all four required REST endpoints, Redis Hash/Set/List persistence, Redis
Pub/Sub → SSE client delivery, automated tests, persistence checks and documentation are
implemented and verified in a running stack.

---

## 1. Executive summary

The existing project already created rooms, tracked participants and stored messages in
Redis, but it was not functionally complete:

- Redis Pub/Sub messages were only written to the server log. Connected clients had no way to
  receive a message without polling, and the bundled demo UI polled history every two seconds.
- Request validation existed in the DTOs but the history `limit` was never enforced
  (`@Min/@Max` on a `@RequestParam` requires `@Validated`), and `default-history-limit` was
  injected but unused.
- Several failure modes returned `500` (non-numeric `limit`, malformed JSON, missing static
  resources) instead of `4xx`.
- Room creation used a non-atomic `hasKey` + `put` pair, so concurrent creates could both
  succeed.
- The Redis `ObjectMapper` used unrestricted Jackson default typing, which allows unsafe
  polymorphic deserialization.
- The four test classes started a Testcontainers Redis but never told Spring about it; they
  would have connected to `localhost:6379` and failed. No test had ever been executed.

All of these are fixed. The service now pushes every persisted message to the SSE
connections of the room it belongs to, exactly once, with room isolation, heartbeats and
cleanup. 62 automated tests pass against a real Redis, and the complete REST surface,
real-time delivery and restart persistence were verified against the running Docker stack.

---

## 2. Architecture and data flow

### 2.1 Components

| Component | Responsibility |
|---|---|
| `ChatController` | Thin HTTP layer: validation, status codes, SSE subscription endpoint |
| `ChatRoomService` | Room lifecycle, participant Set, message List, history queries, trimming |
| `PubSubService` | Publishes a `ChatMessage` to `chat:room:{id}:messages` |
| `ChatMessageListener` | Receives every room channel, resolves the room, forwards to `RealtimeService` |
| `RealtimeService` | Registry of `SseEmitter`s per room, broadcast, heartbeats, cleanup |
| `RedisConfig` | `RedisTemplate` with `StringRedisSerializer` keys and a dedicated JSON value serializer |
| `PubSubConfig` | The single `RedisMessageListenerContainer`, subscribed to `chat:room:*:messages` |
| `GlobalExceptionHandler` | Maps exceptions to a consistent `ApiResponse` envelope |
| `RedisKeys` | Single source of truth for key and channel names |

### 2.2 Write path

```
POST /api/chatapp/chatrooms/{id}/messages
  └─ ChatController.sendMessage            (validation only)
      └─ ChatRoomService.sendMessage
          ├─ requireRoom()                 → 404 if the metadata hash is missing
          ├─ SADD  chat:room:{id}:participants {participant}
          ├─ RPUSH chat:room:{id}:messages  <ChatMessage JSON>
          ├─ LTRIM (size - max, -1)        when the list exceeds max-history-limit
          └─ PUBLISH chat:room:{id}:messages <ChatMessage JSON>
                  └─ ChatMessageListener.onMessage
                      └─ RealtimeService.broadcast(roomId, message)
                          └─ SseEmitter.send("message", json)   → subscribed browsers
```

The REST request never touches an emitter, and the listener is the only producer for SSE.
This is what prevents duplicate delivery.

Ordering matters: the message is persisted **before** it is published, so a client that
receives the event and immediately calls `GET .../messages` sees the message.

### 2.3 Read path

`GET /api/chatapp/chatrooms/{id}/messages?limit=N` → `requiresRoom` → `LLEN` → `LRANGE`
from `size - N` to `-1` → deserialize into `ChatMessage` → return oldest-first.

### 2.4 Real-time path

`GET /api/chatapp/chatrooms/{id}/stream` verifies the room exists, creates an `SseEmitter`
(30 min timeout), registers it in a `ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>`
keyed by room id, and immediately sends a `connected` event. The emitter is removed on
completion, timeout or a failed write; a scheduled task sends `:keep-alive` comments every
15 seconds.

---

## 3. Redis data model

| Key / channel | Type | Fields / members |
|---|---|---|
| `chat:room:{id}:metadata` | Hash | `name`, `createdAt` (ISO-8601 instant) |
| `chat:room:{id}:participants` | Set | participant names |
| `chat:room:{id}:messages` | List | serialized `ChatMessage` objects, oldest at head |
| `chat:room:{id}:messages` | Pub/Sub channel | same JSON payload as the list entry |

Serialization rules:

- Keys and hash keys use `StringRedisSerializer`, so keys are human-readable in `redis-cli`.
- Values use `GenericJackson2JsonRedisSerializer` with a dedicated `ObjectMapper`.
- `JavaTimeModule` is registered; `ChatMessage.timestamp` is an `Instant` and is emitted as
  ISO-8601 (`2026-10-21T14:32:58.285882673Z`).
- Default typing is enabled so list entries deserialize back to `ChatMessage` instead of
  `LinkedHashMap`, but it is restricted with `BasicPolymorphicTypeValidator` to
  `com.freightfox.chatapp.model.`, `java.util.` and `java.time.` subtypes. Arbitrary classes
  referenced in a forged payload cannot be instantiated.

All key construction is centralized in `RedisKeys` (`metadata`, `participants`, `messages`,
`channel`, `channelPattern`, `roomIdFromChannel`).

### 3.1 Key cleanup on delete

`DELETE` performs one `DEL` over the metadata, participants and messages keys, so room
history and membership cannot outlive the room. Verified directly with `redisTemplate.hasKey`
in tests and with `redis-cli EXISTS` on the running stack.

---

## 4. Real-time delivery

### 4.1 Transport choice

**Server-Sent Events** was chosen over STOMP/WebSocket:

- The existing API is plain Spring MVC REST; SSE needs no extra dependency, broker or
  handshake, and `SseEmitter` is native to Spring MVC.
- Chat delivery is one-directional (server → client); sending already has a REST endpoint.
- `EventSource` reconnects automatically and is trivial to consume from the demo UI.

### 4.2 Endpoint contract

**Subscription endpoint:** `GET /api/chatapp/chatrooms/{roomId}/stream` with
`Accept: text/event-stream`.

This endpoint is **additive and optional**: the four required REST endpoints are fully usable
without any streaming client, and a consumer that only needs send/history can ignore it.

| Frame | Meaning |
|---|---|
| `event:connected` + `data:{"roomId":"..."}` + `retry:3000` | subscription accepted |
| `event:message` + `id:<uuid>` + `data:{...ChatMessage...}` | a message was published to this room |
| `:keep-alive` | heartbeat comment, ignored by clients |

Unknown room → `404`. Because the client requested `text/event-stream`, the error is sent as
a single `event: error` frame with the proper status code, rather than a JSON body the client
cannot parse.

**How Redis Pub/Sub forwards a message to clients.** The REST call is the only write path;
the stream is a read-only fan-out:

1. `POST /api/chatapp/chatrooms/{roomId}/messages` validates the body and calls
   `ChatRoomService.sendMessage`.
2. `ChatRoomService` appends the `ChatMessage` to the Redis List
   `chat:room:{roomId}:messages` (`RPUSH`, then `LTRIM` to the configured maximum).
3. It publishes the same serialized `ChatMessage` on the Redis channel
   `chat:room:{roomId}:messages` (`PUBLISH`).
4. `PubSubConfig` registers one `RedisMessageListenerContainer` for the pattern
   `chat:room:*:messages`. For each delivery, `ChatMessageListener.onMessage` deserializes the
   payload, extracts `roomId` from the channel name via `RedisKeys.roomIdFromChannel`, and
   calls `RealtimeService.broadcast(roomId, message)`.
5. `RealtimeService` looks up the emitters registered for that room id and writes one
   `event: message` frame to each. Emitters of other rooms are never touched.

The HTTP request that sends the message never calls an emitter itself, so a subscriber
receives the message exactly once, and only via Redis Pub/Sub. This also means the listener,
not the sender, is the single integration point between Redis and the SSE registry.

### 4.3 Room isolation

Subscribers are stored per room id. `broadcast(roomId, message)` iterates only the list for
`roomId`. A message published on `chat:room:A:messages` is therefore never written to a
client subscribed to `chat:room:B:messages`.

### 4.4 Resource management

- Emitter timeout: `app.chat.sse-timeout-ms` = 30 minutes.
- `onCompletion`, `onTimeout` and `onError` all unregister the emitter.
- A failed `send` (client gone) unregisters and completes the emitter.
- Empty room lists are removed from the registry, so rooms do not leak entries.
- `@PreDestroy` completes every remaining emitter on shutdown.
- Heartbeats every `app.chat.sse-heartbeat-ms` (15 s) keep idle connections alive through
  proxies and surface dead connections.

### 4.5 Verification on the running stack

Two independent SSE clients were opened with `curl -N` from separate containers on the
Compose network (`sse-a` → room `sse-a`, `sse-b` → room `sse-b`). A message was then posted
to room A:

```
[room A stream]
event:connected
retry:3000
data:{"roomId":"sse-a"}

:keep-alive

id:05db6257-c7ce-4140-84b0-30ab5f8a7f03
event:message
data:{"participant":"alice","message":"room A only","timestamp":"2026-10-21T14:33:18.303103430Z"}

[room B stream]
event:connected
retry:3000
data:{"roomId":"sse-b"}

:keep-alive
```

A second message posted to room B produced the symmetric result:

```
A received 'room A only'  : True
B did NOT receive it      : True
B received 'room B only'  : True
A did NOT receive it      : True
```

The same behaviour is asserted automatically by
`ChatIntegrationTest.realTime_deliversOnlyToSubscribersOfTheSameRoom`, which opens two real
HTTP event streams with `java.net.http.HttpClient`, waits for both `connected` frames, posts
to room A, waits for delivery, and asserts room B never received it (and vice versa).

### 4.6 Duplicate-delivery avoidance

`ChatRoomService.sendMessage` used to publish while the controller also triggered history
reloads in the UI. Now:

- Only `ChatMessageListener` writes to `SseEmitter`s.
- The demo UI no longer polls. It loads history once on join (and after a reconnect), then
  appends live events; a client-side dedupe key (`timestamp|participant|message`) guards the
  history/stream overlap.
- `RealtimeService.broadcast` sends one event per broadcast; nothing else calls the emitters.

---

## 5. REST API verification

All requests below were executed against `http://localhost:8080` with the final image.
Full request/response examples are in `README.md`; this section records the observed status
codes.

| # | Scenario | Expected | Observed |
|---|---|---|---|
| 1 | Create room `verify-room` | 200 | 200 `status:success` |
| 2 | Create the same room again | 409 | 409 `Chat room 'verify-room' already exists` |
| 3 | Join existing room | 200 | 200 |
| 4 | Join missing room | 404 | 404 |
| 5 | Send message 1 | 200 | 200 |
| 6 | Send message 2 | 200 | 200 |
| 7 | `GET ...?limit=1` | newest only | `second message` only |
| 8 | `GET ...?limit=2` | both, oldest first | `first message`, `second message` |
| 9 | `limit=0` | 400 | 400 `limit must be at least 1` |
| 10 | `limit=-1` | 400 | 400 `limit must be at least 1` |
| 11 | `limit=101` | 400 | 400 `limit must be at most 100` |
| 12 | `limit=abc` | 400 | 400 `Invalid value 'abc' for parameter 'limit'` |
| 13 | Send to missing room | 404 | 404 |
| 14 | Blank message field | 400 | 400 `message: Message content is required` |
| 15 | Malformed JSON | 400 | 400 `Malformed or missing JSON request body` |
| 16 | Delete room | 200 | 200 |
| 17 | History after delete | 404 | 404 |
| 18 | Delete again | 404 | 404 |
| 19 | `GET /` (demo UI) | 200 | 200 |
| 20 | `GET /swagger-ui/index.html` | 200 | 200 |
| 21 | `GET /api-docs` | 200 | 200 |
| 22 | `GET /actuator/health` | 200 UP | 200, Redis 7.4.11 UP |

### 5.1 Response format

Success and error envelopes are unchanged for the four required endpoints.
`POST /messages`, `POST /join`, `DELETE` and errors return `ApiResponse`
(`message`, `status`, `data`); `GET /messages` returns `ChatHistoryResponse` (`messages`).

The only API changes are additive:

- `GET /{roomId}/stream` (new SSE endpoint).
- `DELETE` on an unknown room now returns `404` instead of `200` (consistent with
  `GET`/`POST`).
- Invalid room ids and out-of-range `limit` values now return `400` instead of being accepted
  or producing a `500`.
- `roomName` is restricted to `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$`; participant and message
  lengths are capped at 64 and 2000 characters.

### 5.2 The demo UI is optional — REST APIs are independently testable

The bundled UI (`src/main/resources/static/index.html`) is a static resource. No controller,
service, listener or test depends on it, and no endpoint requires it. It exists only to
demonstrate the SSE stream interactively.

Evidence that the API stands on its own:

- The Postman collection was executed headlessly with Newman against the running container:
  **14 requests, 34 assertions, 0 failures**, with no browser or UI process involved.
- All 22 REST scenarios in §5 were executed with plain HTTP requests.
- Swagger UI and `curl` consume the same endpoints.
- Deleting `static/index.html` would only make `GET /` return 404; API behaviour is unchanged.

---

## 6. Message history and ordering

`getMessages(roomId, limit)`:

1. `requireRoom` → `404` when the metadata hash is absent.
2. `LLEN`; empty list → `[]`.
3. `effectiveLimit = max(1, min(limit, max-history-limit))`.
4. `LRANGE key (size - effectiveLimit) -1` — always the **newest** N entries.
5. Cast to `ChatMessage`, preserving list order, which is chronological because every write
   is an `RPUSH`.

Verification with two messages (`first message`, then `second message`):

```json
limit=1 → {"messages":[{"participant":"bob","message":"second message","timestamp":"..."}]}
limit=2 → {"messages":[
             {"participant":"alice","message":"first message","timestamp":"...285882673Z"},
             {"participant":"bob","message":"second message","timestamp":"...402815936Z"}]}
```

Timestamps match `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$` (ISO-8601 UTC). This is
asserted both in the controller test and end-to-end in `ChatIntegrationTest`.

### 6.1 Trimming

After `RPUSH`, if the returned size exceeds `app.chat.max-history-limit`, `LTRIM key (size-max) -1`
keeps exactly the newest `max` entries. `ChatRoomServiceTest` runs with
`max-history-limit=3`, writes five messages and asserts:

- history returns `Message 3, Message 4, Message 5`,
- `LLEN` is exactly `3`.

Trimming never reorders: `LTRIM` removes from the head, and history is read from the tail.

### 6.2 Limit validation

`@Validated` on the controller activates method validation for `@Min(1)` / `@Max(100)` on
the `@RequestParam`. `@RequestParam(defaultValue = "${app.chat.default-history-limit:50}")`
now uses the previously dead configuration property. A type mismatch is mapped to `400` by
`MethodArgumentTypeMismatchException`. The service additionally clamps the value, so it is
safe even when called outside HTTP.

---

## 7. Automated tests

### 7.1 Strategy

| Layer | Approach |
|---|---|
| Service | Spring context + **real Redis** (Testcontainers, or external Redis via `TEST_REDIS_HOST`) |
| Controller | `@WebMvcTest` + Mockito, full MVC pipeline (Jackson, validation, advice) |
| Real-time registry | Pure unit tests with a `SseEmitter` subclass that records `send` calls |
| Pub/Sub listener | Mockito with a mocked `RedisTemplate` serializer |
| End-to-end | `@SpringBootTest(RANDOM_PORT)`, `TestRestTemplate` + `java.net.http.HttpClient` for SSE, real Redis |

No Redis mock is presented as an integration test: the service and integration suites run
against a live `redis:7-alpine` server and assert both API responses and raw Redis contents.

### 7.2 Execution result

Command (this environment, real Redis on the Compose network):

```bash
mvn -B test
```

```
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Re-verified after the deployment-configuration cleanup (port binding, production profile
and documentation only; no application or test logic changed): the same 62 tests passed
with 0 failures, 0 errors and 0 skipped.

| Class | Tests | Result | Time |
|---|---|---|---|
| `ChatApplicationTests` | 1 | pass | 22.5 s (context + Redis) |
| `ChatRoomServiceTest` | 14 | pass | 5.0 s |
| `ChatControllerTest` | 22 | pass | 5.5 s |
| `ChatIntegrationTest` | 12 | pass | 9.4 s |
| `ChatMessageListenerTest` | 4 | pass | 1.7 s |
| `PubSubServiceTest` | 1 | pass | 0.03 s |
| `RealtimeServiceTest` | 8 | pass | 0.03 s |
| **Total** | **62** | **0 failures, 0 skipped** | |

### 7.3 Coverage of the required behaviours

| Required behaviour | Test |
|---|---|
| Room creation | `createRoom_storesMetadataHash`, `createJoinSendAndRead_worksEndToEnd` |
| Duplicate creation | `createRoom_whenRoomExists_throws`, `duplicateRoomCreation_returns409` |
| Concurrent creation | `createRoom_concurrentRequests_onlyOneSucceeds` (6 threads, exactly 1 success) |
| Join existing / missing room | `joinRoom_addsParticipantToSet`, `joinMissingRoom_returns404`, `joiningRoom_updatesParticipantSet` |
| Send + Redis persistence | `sendMessage_persistsMessageAndRegistersParticipant`, integration test also checks `LLEN` |
| Missing room on send | `sendMessage_whenRoomMissing_throws`, `sendToMissingRoom_returns404` |
| History ordering and limits | `getMessages_returnsNewestNInChronologicalOrder`, `history_returnsNewestForLimitOne_...`, `invalidLimits_return400` |
| Room deletion and key cleanup | `deleteRoom_removesMetadataParticipantsAndMessages`, `deleteRoom_removesEverythingAndHistoryBecomesUnavailable` |
| Pub/Sub publication | `publishMessage_sendsToRoomChannel` |
| Pub/Sub listener → client | `onMessage_forwardsPayloadToTheRoomFromTheChannel`, `broadcast_reachesAllSubscribersOfTheRoom` |
| Room isolation | `broadcast_doesNotReachOtherRooms`, `realTime_deliversOnlyToSubscribersOfTheSameRoom` |
| Input validation / exception mapping | 11 controller tests + `invalidPayloads_return400WithErrorBody` |
| SSE cleanup / heartbeat | `deadSubscriber_isRemovedWhenDeliveryFails`, `heartbeat_sendsCommentToEverySubscriber`, `completeAll_clearsEveryRoom` |

### 7.4 Environment note (honest limitation)

Testcontainers normally starts its own Redis. On this machine Docker Desktop exposes a
socket proxy that rejects API versions below `1.44`, which the Docker client embedded in
Testcontainers probes with; the result is `Could not find a valid Docker environment`
(`Status 400` from `/v1.32/info`). This is a daemon/socket-proxy constraint, not a code
defect.

To still execute the tests against real Redis, `RedisTestContainer.register` supports an
external server via `TEST_REDIS_HOST` / `TEST_REDIS_PORT`, and the suite was run in a Maven
container attached to the Compose network with a dedicated `freightfox-test-redis` instance:

```bash
docker run -d --name freightfox-test-redis --network freightfox_default \
  redis:7-alpine redis-server --save "" --appendonly no

docker run --rm --network freightfox_default \
  -v "$PWD:/workspace" -w /workspace \
  -e TEST_REDIS_HOST=freightfox-test-redis -e TEST_REDIS_PORT=6379 \
  maven:3.9.6-eclipse-temurin-21 mvn -B test
```

On a normal Docker host the default path (`mvn test`, Testcontainers) needs no environment
variables. Testcontainers was upgraded from 1.19.8 to 1.21.3 for current Docker engine
compatibility.

---

## 8. Postman collection

`postman/Freightfox-Chat.postman_collection.json` contains 14 requests / 34 assertions:

1. health check (app + Redis UP)
2. create room (uses a per-run `postman-room-<timestamp>`)
3. duplicate room → 409
4. join room
5. join missing room → 404
6. send first message
7. send second message
8. latest message `limit=1`
9. history `limit=2` with ordering assertions
10. send to missing room → 404
11. invalid body → 400
12. invalid limit → 400
13. delete room
14. history after delete → 404

The collection description documents real-time testing with two clients (two `curl -N`
subscribers, one per room).

Executed headlessly with Newman against the running container:

```
requests: 14   failed: 0
assertions: 34 failed: 0
NEWMAN_EXIT=0
```

---

## 9. Redis persistence and Docker

### 9.1 Compose configuration

- `redis`: `redis:7-alpine`, AOF (`--appendonly yes`), 256 MB `allkeys-lru`, healthcheck
  via `redis-cli ping`, data on the `redis_data` volume, `restart: unless-stopped`.
- `app`: built from the multi-stage `Dockerfile` (Maven build → `eclipse-temurin:21-jre-alpine`),
  port `8080`, `SPRING_DATA_REDIS_HOST=redis`, `restart: unless-stopped`,
  `depends_on: redis: condition: service_healthy`.

Redis hostname inside Docker is `redis`; outside it defaults to `localhost`. All settings are
externalized and overridable through environment variables.

### 9.2 Restart verification

```
Before restart:  {"messages":[{"participant":"alice","message":"survive the restart",
                              "timestamp":"2026-10-21T14:33:32.390567751Z"}]}
AOF files:       appendonly.aof.1.base.rdb / appendonly.aof.1.incr.aof / manifest
Command:         docker compose restart app redis
After restart:   {"status":"UP", ... "redis":{"status":"UP","details":{"version":"7.4.11"}}}
History:         {"messages":[{"participant":"alice","message":"survive the restart",
                              "timestamp":"2026-10-21T14:33:32.390567751Z"}]}
```

The message and its timestamp survived a restart of both containers. AOF files were present
in `/data/appendonlydir` on the volume. Recreating containers (`docker compose down` without
`-v`) keeps the volume; `down -v` deletes it, as documented in the README.

### 9.3 Health

`GET /actuator/health` returns `UP` with `components.redis.status = UP` and Redis version
`7.4.11`; the demo UI displays the same status. This verbose detail is intentional for the
local/default profile.

### 9.4 Render deployment readiness

The repository includes a Render Blueprint and the production settings it activates:

- `render.yaml` provisions the Docker web service and a private Render Key Value instance.
  The web service receives `SPRING_DATA_REDIS_URL` from the Key Value
  `connectionString`, so no Redis credentials are stored in the repository.
- `SPRING_PROFILES_ACTIVE=prod` loads `application-prod.yml`, which exposes only
  `health` and `info`, sets `show-details: never`, and lowers application/Redis logging
  to `INFO`.
- `application.yml` honours the platform-injected `PORT` (`${PORT:8080}`) and binds
  `0.0.0.0`; the Docker health check uses `/actuator/health`.
- `.env.example` documents the Compose variables and the production variable names
  without containing any secret values.
- `.dockerignore` keeps `.git`, `target`, local environment files and documentation out
  of the Docker build context.

No public Render deployment was performed as part of this repository audit, so no live
URL is claimed. The Blueprint follows the documented Render schema; deployment must be
verified in the target Render account.

---

## 10. Defects fixed

| ID | Defect | Fix |
|---|---|---|
| D1 | Pub/Sub messages only logged; no client delivery | `ChatMessageListener` → `RealtimeService` → SSE; `GET /{roomId}/stream` |
| D2 | Demo UI polled every 2 s | `EventSource` with reconnect + history resync; polling removed |
| D3 | `ObjectMapper` missing `JavaTimeModule`; every `sendMessage` failed on `Instant` | Dedicated Redis `ObjectMapper` with `JavaTimeModule` |
| D4 | Two listener containers, neither registered | One container in `PubSubConfig`, subscribed to `chat:room:*:messages` |
| D5 | `@Min/@Max` on `limit` not enforced (no `@Validated`) | `@Validated` on the controller + method validation handling |
| D6 | `default-history-limit` injected but unused | Used as the `@RequestParam` default via placeholder |
| D7 | Non-numeric `limit`, malformed JSON, unknown paths → 500 | Explicit handlers → 400/404/405/415 |
| D8 | Room creation race (`hasKey` then `put`) | Atomic `HSETNX` (`opsForHash().putIfAbsent`) |
| D9 | `DELETE` on missing room returned 200 | `requireRoom` → 404 |
| D10 | Unrestricted Jackson default typing | `BasicPolymorphicTypeValidator` restricted to model/JDK types |
| D11 | Key strings duplicated across services | `RedisKeys` helper |
| D12 | Tests started a container but Spring used `localhost`; never executed | `DynamicPropertySource` wiring + external-Redis fallback; 62 tests run |
| D13 | Unused Lombok dependency and dead `@EnableRedisRepositories` | Removed (the latter also broke MVC slice tests) |
| D14 | `sendMessage` logic in the controller | Moved into `ChatRoomService`; controller is thin |

---

## 11. Configuration reference

```yaml
server.port: ${PORT:8080}             # platform-injected port, 8080 locally
server.address: 0.0.0.0               # reachable inside Docker/Render
app.chat.max-history-limit: 100       # messages kept per room
app.chat.default-history-limit: 50    # limit used when ?limit is omitted
app.chat.sse-timeout-ms: 1800000      # 30 minutes per SSE connection
app.chat.sse-heartbeat-ms: 15000      # keep-alive comment interval
spring.data.redis.host / port         # localhost:6379, "redis:6379" in Compose
spring.data.redis.url                 # set by Render from Key Value connectionString
```

`application-prod.yml` is activated with `SPRING_PROFILES_ACTIVE=prod` and restricts the
public Actuator surface to `health,info` with hidden details.

Redis persistence is controlled by the Redis command line in `docker-compose.yml`; the app
does not manage it.

---

## 12. Known limitations

The first three items are deliberate design decisions for this assignment, disclosed so they
can be judged explicitly. None of them prevents the four required endpoints, Redis
persistence, Pub/Sub delivery or room isolation from working.

### 12.1 Design limitations (intentional)

1. **No authentication or authorization.** Any caller that can reach the API can create or
   delete rooms and post under any participant name; `participant` is taken from the request
   body. The assignment did not require auth. A production build would use Spring Security,
   take identity from the authenticated principal instead of the request body, and add rate
   limiting and TLS termination.

2. **The SSE registry is per application instance.** `RealtimeService` keeps `SseEmitter`s in
   an in-memory map, so an instance writes only to the clients connected to that instance.
   Redis Pub/Sub is shared — every instance receives every published message — so the
   delivered single-container Compose deployment behaves correctly. Horizontal scaling would
   require sticky sessions or a shared relay (STOMP with a Redis-backed broker, or a fan-out
   service); alternatively clients can reconnect and resync history via the REST endpoint.
   This is a scaling limitation, not a correctness issue for the submitted deployment.

3. **Posting implicitly joins the sender.** `ChatRoomService.sendMessage` performs `SADD` on
   the room's participants Set before storing the message. This keeps membership accurate
   without returning `403` to clients that send before calling `/join`, and preserves the
   required API flow. Explicit `POST /join` remains supported. The consequence is that
   `participant` is a self-declared presence marker, not an authorization decision.

### 12.2 Scope limitations

- **No room or participant listing endpoint.** `SMEMBERS` / `KEYS` are available through
  `redis-cli`; listing was not part of the assignment.
- **Demo UI has no message pagination**; it requests the newest 100 messages. The UI is
  optional — see §5.2.
- **`allkeys-lru` with 256 MB** can evict rooms under memory pressure. Acceptable for this
  assignment; a production deployment would pin the policy or shard by room.

### 12.3 Environment limitation (test execution only)

- **The Testcontainers path was not executed on this host** because the Docker Desktop socket
  proxy rejects API versions below `1.44`, which the embedded Docker client probes with. The
  suite was executed against a dedicated, real Redis container instead (§7.4) — no mocks were
  substituted. On a normal Docker host, `mvn test` uses Testcontainers with no extra
  configuration.

---

## 13. How to run and verify

```bash
# 1. Start everything
docker compose up -d --build

# 2. Health
curl http://localhost:8080/actuator/health

# 3. Required REST flow
curl -X POST http://localhost:8080/api/chatapp/chatrooms \
  -H "Content-Type: application/json" -d '{"roomName":"general"}'
curl -X POST http://localhost:8080/api/chatapp/chatrooms/general/join \
  -H "Content-Type: application/json" -d '{"participant":"alice"}'
curl -X POST http://localhost:8080/api/chatapp/chatrooms/general/messages \
  -H "Content-Type: application/json" -d '{"participant":"alice","message":"hello"}'
curl "http://localhost:8080/api/chatapp/chatrooms/general/messages?limit=1"

# 4. Real-time: two terminals
curl -N -H "Accept: text/event-stream" \
  http://localhost:8080/api/chatapp/chatrooms/general/stream

# 5. Demo UI / docs
#    http://localhost:8080/  ·  /swagger-ui.html  ·  /api-docs

# 6. Tests (normal Docker host)
mvn test

# 7. Postman
docker run --rm --network freightfox_default \
  -v "$PWD/postman:/etc/newman" postman/newman \
  run /etc/newman/Freightfox-Chat.postman_collection.json \
  --env-var baseUrl=http://freightfox-app-1:8080
```

---

## 14. Repository layout

```
.
├── .dockerignore                  Docker build-context exclusions
├── .env.example                   safe Compose variable template
├── Dockerfile                     multi-stage build (Maven → JRE 21 alpine)
├── docker-compose.yml             app + redis, healthcheck, AOF volume, restart policy
├── render.yaml                    Render Blueprint (web service + private Key Value)
├── pom.xml                        Spring Boot 3.2.2, springdoc 2.3.0, Testcontainers 1.21.3
├── README.md
├── REPORT.md
├── postman/
│   └── Freightfox-Chat.postman_collection.json
└── src
    ├── main/java/com/freightfox/chatapp
    │   ├── config/     RedisConfig, PubSubConfig, OpenApiConfig
    │   ├── controller/ ChatController
    │   ├── exception/  GlobalExceptionHandler, RoomNotFoundException, RoomAlreadyExistsException
    │   ├── listener/   ChatMessageListener
    │   ├── model/      ChatMessage, ApiResponse, ChatHistoryResponse, RoomInfoResponse,
    │   │               CreateRoomRequest, JoinRoomRequest, SendMessageRequest, ValidationRules
    │   ├── redis/      RedisKeys
    │   ├── service/    ChatRoomService, PubSubService, RealtimeService
    │   └── ChatApplication.java
    ├── main/resources
    │   ├── application.yml
    │   ├── application-prod.yml  public-surface hardening
    │   └── static/index.html      SSE demo UI
    └── test/java/com/freightfox/chatapp
        ├── controller/ChatControllerTest.java
        ├── integration/ChatIntegrationTest.java
        ├── listener/ChatMessageListenerTest.java
        ├── service/ChatRoomServiceTest.java, PubSubServiceTest.java, RealtimeServiceTest.java
        ├── support/RedisTestContainer.java
        └── ChatApplicationTests.java
```

---

## Appendix A — Requirements checklist

| Requirement | Status | Evidence |
|---|---|---|
| Four required REST endpoints preserved | ✅ | §5, live run |
| REST API testable without the demo UI | ✅ | §5.2 — 14 Postman requests / 34 assertions with no browser |
| SSE subscription endpoint documented and explained | ✅ | §4.2, README §Real-time subscription |
| Design limitations disclosed accurately | ✅ | §12.1 (per-instance SSE, no auth, implicit join), README §Design decisions |
| Duplicate room → 409 | ✅ | §5 #2, `duplicateRoomCreation_returns409` |
| Missing room → 404 | ✅ | §5 #4, #13, #17 |
| Invalid fields → 400 | ✅ | §5 #9–#12, #14, #15 |
| Redis Hash/Set/List used correctly | ✅ | §3, service tests, `redis-cli` |
| Pub/Sub broadcasts to real client subscribers | ✅ | §4.5 two-client demonstration, integration test |
| Room isolation | ✅ | §4.5, `realTime_deliversOnlyToSubscribersOfTheSameRoom` |
| No duplicate delivery | ✅ | single broadcast path, §4.6 |
| Connection cleanup / heartbeat / error handling | ✅ | §4.4, `RealtimeServiceTest` |
| History limit=1 newest only | ✅ | §6 |
| History limit=2 chronological | ✅ | §6 |
| ISO-8601 UTC timestamps | ✅ | §6, controller/integration assertions |
| Limit validation (0, negative, non-numeric, >max) | ✅ | §5 #9–#12 |
| Redis trimming preserves ordering | ✅ | §6.1 |
| Concurrent-safe room creation | ✅ | atomic `HSETNX`, concurrency test |
| Participant Set correctness | ✅ | join/leave tests, send auto-join |
| Room deletion removes all keys | ✅ | §3.1, delete tests |
| `default-history-limit` wired | ✅ | §6.2 |
| `max-history-limit` enforced | ✅ | §6.1 |
| Restricted Jackson default typing | ✅ | §3 |
| Single listener container | ✅ | `PubSubConfig` |
| Shared key helper | ✅ | `RedisKeys` |
| Consistent status codes / error envelope | ✅ | §5.1 |
| Thin controller, logic in service | ✅ | §2 |
| Dead code/debug/unused deps removed | ✅ | D13 |
| Unit tests executed | ✅ | §7 — 35 tests (22 `@WebMvcTest`, 13 pure unit) |
| Integration tests executed (real Redis) | ✅ | §7 — 26 real-Redis tests + 1 context test |
| Swagger/OpenAPI retained + real-time documented | ✅ | `/api-docs`, `/swagger-ui.html`, `OpenApiConfig` |
| Postman collection created and executed | ✅ | §8 — 34/34 assertions |
| Docker stack starts app + Redis with healthcheck | ✅ | §9 |
| AOF persistence across restarts | ✅ | §9.2 |
| README and REPORT updated | ✅ | this file, `README.md` |
| Testcontainers executed in this environment | ⚠️ not possible | §7.4 — Docker socket proxy; external real Redis used instead; `mvn test` works on a normal Docker host |
| Authentication/authorization | ⚠️ out of scope | §12 |

---

## Appendix B — Files changed in this delivery

**Added**

- `src/main/java/com/freightfox/chatapp/service/RealtimeService.java`
- `src/main/java/com/freightfox/chatapp/redis/RedisKeys.java`
- `src/main/java/com/freightfox/chatapp/config/OpenApiConfig.java`
- `src/main/java/com/freightfox/chatapp/model/ValidationRules.java`
- `src/test/java/com/freightfox/chatapp/support/RedisTestContainer.java`
- `src/test/java/com/freightfox/chatapp/service/RealtimeServiceTest.java`
- `src/test/java/com/freightfox/chatapp/service/PubSubServiceTest.java`
- `src/test/java/com/freightfox/chatapp/listener/ChatMessageListenerTest.java`
- `postman/Freightfox-Chat.postman_collection.json`

**Modified**

- `src/main/java/com/freightfox/chatapp/controller/ChatController.java` — SSE endpoint, validation, configurable default limit, OpenAPI annotations
- `src/main/java/com/freightfox/chatapp/service/ChatRoomService.java` — atomic create, publish on send, trim, requireRoom, delete semantics
- `src/main/java/com/freightfox/chatapp/service/PubSubService.java` — uses `RedisKeys`
- `src/main/java/com/freightfox/chatapp/listener/ChatMessageListener.java` — forwards to SSE
- `src/main/java/com/freightfox/chatapp/config/RedisConfig.java` — restricted typing, dedicated mapper
- `src/main/java/com/freightfox/chatapp/config/PubSubConfig.java` — single container
- `src/main/java/com/freightfox/chatapp/exception/GlobalExceptionHandler.java` — additional mappings + SSE-aware errors
- `src/main/java/com/freightfox/chatapp/ChatApplication.java` — `@EnableScheduling`, removed dead Redis repository scanning
- `src/main/java/com/freightfox/chatapp/model/CreateRoomRequest.java`, `JoinRoomRequest.java`, `SendMessageRequest.java` — validation
- `src/main/resources/application.yml` — SSE settings
- `src/main/resources/static/index.html` — SSE UI, no polling
- `src/test/java/...` — all four test classes reworked, 62 tests
- `pom.xml` — Testcontainers 1.21.3, removed Lombok and its processor config
- `docker-compose.yml` — `restart: unless-stopped`
- `README.md`, `REPORT.md`

**Removed**

- Unused Lombok dependency and compiler plugin configuration
- `@EnableRedisRepositories` (no Redis repositories exist; it broke `@WebMvcTest` slices)
- Unused `PubSubService.getRoomChannel`

**Deployment-readiness additions (audit)**

- `.env.example` — non-secret template for Docker Compose variables.
- `render.yaml` — Render web service plus private Key Value instance.
- `src/main/resources/application-prod.yml` — hardened production profile.
- `.dockerignore` — excludes Git metadata, build output and local environment files from
  the Docker build context.
- `application.yml` — honours `${PORT:8080}` and binds `0.0.0.0`.
- `docker-compose.yml` — Redis host/port are overridable through `.env`, defaulting to the
  `redis` service.
- `README.md` / `REPORT.md` — Render deployment and environment documentation.

No application, test or API logic was changed by this cleanup, and no unrelated Docker
containers, volumes or images were modified.
