# OpenGPT: Architecture and AI Coding Map

This file is the primary repository guide for coding agents and maintainers. Read it before changing the project. It describes the system boundary, runtime flows, every production source file and class, test coverage, important invariants, and the files that normally need to change together.

When this document conflicts with executable code, the code and tests are authoritative. Update this document when a change alters responsibilities, flows, endpoints, configuration, or file locations.

## 1. Project at a glance

- Purpose: expose a local OpenAI-compatible API backed by a user's ChatGPT Plus/Pro OAuth session and the ChatGPT Codex Responses backend.
- Intended clients: Cursor, OpenCode, and other OpenAI-compatible clients.
- Main API address: `127.0.0.1:5080`.
- OAuth callback address: `127.0.0.1:1455`.
- Upstream endpoint: `https://chatgpt.com/backend-api/codex/responses`.
- Language/runtime: Kotlin 2.2 on Java 21.
- Framework: Spring Boot 4 using a servlet application with Spring MVC controllers and a reactive `WebClient` for upstream SSE.
- Build system: Maven.
- Persistence: one local JSON credentials file; there is no database.
- Deployment model: local, single-user, and single-process. It is not a hosted or multi-tenant gateway.

The adapter handles two related API shapes:

1. `/v1/responses` accepts a Responses-style request, applies required Codex defaults, and proxies upstream SSE data events.
2. `/v1/chat/completions` accepts Chat Completions requests, lowers them to Responses format, sends them to Codex, and translates Codex SSE events back into Chat Completions chunks or an aggregated JSON response.

## 2. System boundary

```text
OpenAI-compatible client
  |
  | Authorization: Bearer <local sk-cla key>
  | POST /v1/chat/completions or /v1/responses
  v
Spring MVC controllers on 127.0.0.1:5080
  |
  +--> local API-key filter
  +--> request mapping/model defaults
  +--> SSE proxy and stream translation
  |
  | ChatGPT OAuth access token
  | POST /backend-api/codex/responses
  v
ChatGPT Codex backend
```

Authentication has a separate browser flow:

```text
Browser -> GET :5080/auth/login
        -> OpenAI authorize endpoint
        -> GET :1455/auth/callback
        -> OpenAI token endpoint
        -> ~/.opengpt/auth.json
```

The OAuth callback must remain on port `1455` because that redirect URI is registered for the Codex OAuth client. The main API and callback listener are separate HTTP servers.

## 3. Architectural layers and dependency direction

The package structure is organized by responsibility:

- `api`: inbound HTTP controllers and API error conversion.
- `config`: typed configuration, shared HTTP client setup, and `/v1` local-key authentication.
- `auth`: OAuth, token refresh, credential persistence, JWT claim extraction, and local key generation.
- `gateway`: request-shape detection at the compatibility boundary.
- `codex`: Chat-to-Responses mapping, model policy, Codex HTTP client, reasoning continuity, call-ID normalization, and Cursor image recovery.
- `streaming`: upstream SSE decoding and Responses-to-Chat-Completions translation.
- `debug`: append-only request/response traffic logging.
- `model`: persisted domain data.
- `util`: small JSON compatibility helpers.

The normal dependency direction is:

```text
api/config
    -> gateway/codex/streaming/auth/debug
        -> model/util/config
```

Keep transport concerns in `api` and `streaming`, request-shape transformation in `codex`, and credentials in `auth`. Avoid moving client-specific compatibility rules into controllers.

### Hybrid servlet/reactive design

The application is explicitly configured as a servlet application. Controllers write to `HttpServletResponse` and block the request thread with Reactor's `blockLast()` or `block()`. The upstream Codex connection remains reactive through `WebClient` and `Flux<DataBuffer>`.

This is deliberate current behavior. Changing to fully reactive controllers or to a blocking upstream client affects disconnect handling, backpressure, SSE flushing, keepalives, exception routing, and tests. Treat that as an architectural change, not a local refactor.

## 4. Runtime flows

### 4.1 Startup

1. `main()` starts `OpengptApplication`.
2. `AppConfig` binds configuration properties and creates the shared `WebClient.Builder`.
3. `TrafficDebugLog` creates the configured log directory and appends an adapter-start marker.
4. Spring MVC starts on `127.0.0.1:5080`.
5. The OAuth callback listener on port `1455` is lazy: it starts when `/auth/login` calls `OAuthCallbackServer.ensureStarted()`.

### 4.2 OAuth login

Browser callback flow:

1. `OAuthController.login()` ensures that the callback server is running.
2. `OAuthService.buildAuthorizationUrl()` clears any pending device-code login, creates a PKCE verifier/challenge and random state, then stores one pending browser login in memory.
3. The browser is redirected to OpenAI.
4. OpenAI redirects to `http://localhost:1455/auth/callback`.
5. `OAuthCallbackServer` validates that code and state exist and delegates to `OAuthService.handleCallback()`.
6. `OAuthService` atomically consumes the pending login, verifies the state, exchanges the code against the localhost redirect URI, extracts account/residency JWT claims, and saves the result.
7. `FileTokenStore` generates a local `sk-cla-...` API key if one is not already present and writes the credentials file.
8. The callback page displays the local base URL and generated local API key.

Device-code flow (Codex headless login):

1. `POST /auth/device/start` calls `OAuthService.startDeviceCodeLogin()`, which clears any pending browser login and POSTs `{ client_id }` to `{issuer}/api/accounts/deviceauth/usercode`.
2. The response stores `device_auth_id`, `user_code`, poll interval, and a 15-minute expiry in a second `AtomicReference`.
3. The UI shows `{issuer}/codex/device` plus the one-time user code.
4. `POST /auth/device/poll` calls `OAuthService.pollDeviceCodeLogin()`, which POSTs to `{issuer}/api/accounts/deviceauth/token`.
5. HTTP 403/404 (or authorization-pending error bodies) mean still waiting; success returns `authorization_code` + `code_verifier` generated by OpenAI.
6. The service exchanges that code with redirect URI `{issuer}/deviceauth/callback` (not port `1455`), then saves the same `OAuthToken` shape as browser login.
7. A 404 on the usercode endpoint becomes `DeviceCodeNotEnabledException` (ChatGPT Security “Device code authorization” disabled or unsupported).

There can be only one pending browser OAuth attempt and one pending device-code attempt because `OAuthService` stores each in an `AtomicReference`. Starting either flow clears the other. This matches the single-user design.

Current OAuth-state caveats:

- Starting a second browser login replaces the first pending verifier/state.
- Starting device-code login clears pending browser state, and vice versa.
- `PendingOAuth.createdAt` is recorded but never checked, so pending browser logins do not expire.
- Device-code pending state expires after 15 minutes.
- `handleCallback()` clears the pending value before validating state; a wrong-state callback consumes the legitimate pending login.

### 4.3 Token use and refresh

1. An upstream request asks `TokenRefreshService` for a valid token.
2. A token with more than 30 seconds remaining is returned unchanged.
3. An expiring token is refreshed through `OAuthService.refresh()`.
4. `refreshInFlight` makes losing callers join the selected future, but each contender currently starts its future before the compare-and-set. It is not strict deduplication: simultaneous refresh calls can still execute and save credentials more than once.
5. The existing local API key is preserved across token refresh.
6. Refresh failures are exposed as `AuthenticationRequiredException`, which becomes an HTTP 401 when it reaches the API exception handler.

### 4.4 Local `/v1` authentication

1. `LocalApiKeyFilter` applies to every path beginning with `/v1/`.
2. It loads the local key from `TokenStore`.
3. It accepts `Authorization: Bearer <key>`; for client compatibility, a raw header value is also treated as the key.
4. Missing credentials produce an OpenAI-shaped 401 `authentication_error`.
5. A wrong local key produces an OpenAI-shaped 401 `invalid_api_key`.

The local `sk-cla-...` key is not an OpenAI Platform API key and is never sent upstream.

### 4.5 Streaming `/v1/chat/completions`

1. `ChatCompletionsController` allocates a traffic-log exchange ID and logs the unredacted client request.
2. `RequestTypeDetector` allows Responses-shaped input on this endpoint for compatibility. Normal Chat Completions input goes to `CodexRequestMapper`.
3. `CodexRequestMapper`:
   - combines `system` and `developer` messages into `instructions`;
   - maps user and assistant message content to Responses input items;
   - maps images to `input_image`;
   - maps function calls/results and custom tool calls/results;
   - normalizes overlong call IDs;
   - restores cached encrypted reasoning for assistant history;
   - copies sampling and reasoning settings;
   - sets `store: false`.
4. `SseProxy.prepareResponsesBody()` normalizes explicit effort fields, resolves effort-specific model aliases, applies/defaults reasoning effort, requests automatic reasoning summaries and encrypted reasoning content, forces streaming, and defaults `store` to false.
5. `CodexClient` gets a valid OAuth token, sets ChatGPT account/residency headers, and opens the upstream SSE stream.
6. `SseProxy` decodes upstream SSE blocks into JSON payloads.
7. One `StreamTranslator.Session` translates the ordered event stream into OpenAI Chat Completions chunks.
8. A no-op Chat Completions chunk is emitted every 10 seconds while upstream translation is still active. This prevents Cursor/proxies from treating buffered reasoning or custom-tool generation as a dead stream.
9. The controller writes and flushes every frame immediately.
10. The stream ends with `data: [DONE]`.
11. Completed encrypted reasoning items are cached by tool-call ID and/or assistant-text hash for a later turn.
12. Both upstream and downstream traffic are appended to the traffic log.

### 4.6 Non-streaming `/v1/chat/completions`

When `stream` is false, the same upstream streaming path is used internally. `SseProxy.proxyAsChatCompletionJson()` collects translated chunks and builds one `chat.completion` response containing assistant text, finish reason, and usage.

Current limitation: the non-streaming aggregator collects text but does not reconstruct `message.tool_calls`. Do not assume non-streaming tool calls are supported without extending this method and adding tests.

### 4.7 `/v1/responses`

`ResponsesController` sends the request through `SseProxy.proxyResponsesSse()`. The proxy still prepares the body: explicit effort normalization, model resolution, reasoning defaults, encrypted-reasoning inclusion, forced streaming, and a default `store: false` are applied. An explicit `store: true` is preserved.

Upstream `data:` payloads are emitted as downstream SSE frames. This is a semantic proxy, not a byte-for-byte pass-through: event names, IDs, comments, retry fields, and upstream `[DONE]` are discarded. No synthetic `[DONE]` or 10-second keepalive is added, and direct Responses output does not populate `ReasoningStateCache`.

### 4.8 Tool-call compatibility

Function calls are streamed in standard Chat Completions `tool_calls` form. Tool indexes are dense Chat Completions indexes keyed by Responses `item_id`/`call_id`; upstream `output_index` must not be used as the Chat Completions tool index.

Codex custom tools, especially `ApplyPatch`, need special treatment:

- Upstream custom-tool input deltas are buffered to avoid thousands of small chunks and Cursor retry failures.
- A completed custom tool is emitted to Cursor as a function-shaped tool call with the raw freeform patch in `function.arguments`.
- On the following request, `CodexRequestMapper` recognizes the custom tool name and promotes that history back to `custom_tool_call`.
- Do not JSON-wrap an `ApplyPatch` body unless the receiving client's contract changes. Cursor expects the raw `*** Begin Patch` text.

### 4.9 Reasoning continuity

Chat Completions clients do not round-trip Responses reasoning items. Because Chat requests use `store: false`, the adapter:

1. requests `reasoning.encrypted_content`;
2. captures completed encrypted reasoning items in `StreamTranslator.Session`;
3. stores deep copies in `ReasoningStateCache`;
4. associates them with normalized tool-call IDs or a SHA-256 hash of assistant text;
5. injects them before matching assistant history on a later request.

The cache is in-memory, process-local, and expires entries after two hours. It is not account- or conversation-scoped; this is acceptable only under the documented single-user assumption.

### 4.10 Cursor image recovery

Cursor can replace an attached image with a small black placeholder while including a local path inside an `<image_files>` text block. `CursorImageRecovery` detects zero-dimension, very small, or nearly uniform-dark images and searches for the original near the hinted path and in common user image directories. A recovered file is embedded as a data URL.

This feature reads local files named by client content and uploads recovered bytes to Codex. A caller holding the local API key can therefore induce local-file reads; files are read fully with no size ceiling, and an undecodable non-image can pass through with a fallback image MIME type. Treat this as a local-file exfiltration boundary, especially if `/v1` is tunneled. Preserve or strengthen path restrictions, size limits, and image validation when changing it.

### 4.11 Errors and client disconnects

- `ApiExceptionHandler` converts authentication, upstream backend, request-status, IO, and generic exceptions to OpenAI-shaped errors.
- Upstream non-2xx responses become `CodexBackendException` and then an HTTP 502 without returning the upstream body to the client.
- `ChatCompletionsController` recursively recognizes broken pipes, servlet async disconnects, and client-abort exceptions. `ResponsesController` has a narrower top-level check and does not explicitly recognize `"Broken pipe"`; some remaining disconnects may be handled later by `ApiExceptionHandler`.
- Translation failures for individual upstream SSE events are logged and skipped, allowing the remaining stream to continue.
- `StreamTranslator` currently converts `response.failed`, `error`, and `response.incomplete` events into ordinary finish chunks (`stop` or `tool_calls`). The Chat Completions client is not told that the upstream response failed or was incomplete.
- Unhandled generic and IO errors can return their exception message to the client, which may expose internal details.
- Both streaming controllers commit HTTP 200 before the upstream subscription finishes authentication/connection setup, so a later 401/502 cannot always be represented as a clean JSON error.

## 5. HTTP endpoint map

- `GET /health`: public liveness response `{ "status": "UP" }`.
- `GET /` and `GET /index.html`: public local login/configuration UI.
- `GET /auth/login`: public browser OAuth start; starts the callback listener and redirects to OpenAI.
- `POST /auth/device/start`: public Codex device-code start; returns verification URL, user code, and poll interval.
- `POST /auth/device/poll`: public device-code poll; completes token exchange when approved.
- `GET /auth/callback`: fallback callback handler on the main server. The registered browser flow normally reaches the separate port-1455 listener.
- `GET /auth/status`: public authentication metadata; does not return tokens or the local API key.
- `POST /auth/regenerate-key`: regenerates the local client key and redirects home.
- `GET /v1/models`: local-key protected model list.
- `POST /v1/responses`: local-key protected Responses SSE proxy.
- `POST /v1/chat/completions`: local-key protected Chat Completions streaming/non-streaming adapter.

## 6. Complete production file and class map

### Application entry point

#### `src/main/kotlin/com/opengpt/OpengptApplication.kt`

- `OpengptApplication`: Spring Boot component-scan and auto-configuration root.
- `main(args)`: JVM entry point.
- Change when: changing application-wide Spring bootstrapping or package scanning.

### `config`

#### `src/main/kotlin/com/opengpt/config/ApplicationProperties.kt`

- `AdapterProperties`: binds `adapter.*`; owns credential path, client-facing base URL, and traffic-log path.
- `OAuthProperties`: binds `openai.oauth.*`; owns OAuth client, issuer, redirect, scope, and originator settings.
- `CodexProperties`: binds `codex.*`; owns upstream endpoint and identity headers.
- Change with: `src/main/resources/application.yml`, test profile configuration, and any consumers of new/renamed properties.

#### `src/main/kotlin/com/opengpt/config/AppConfig.kt`

- `AppConfig`: enables all typed properties and exposes the shared `WebClient.Builder`.
- The HTTP client has a 10-minute response timeout and a 16 MiB codec in-memory limit.
- Collaborators: `OAuthService` and `CodexClient` each build a client from this builder.
- Change when: adjusting all outbound HTTP behavior. Consider both token requests and long-running Codex SSE.

#### `src/main/kotlin/com/opengpt/config/LocalApiKeyFilter.kt`

- `LocalApiKeyFilter`: `OncePerRequestFilter` protecting all `/v1/*` routes with the locally stored API key.
- Collaborator: `TokenStore`.
- Owns pre-controller 401 error bodies, so those errors do not pass through `ApiExceptionHandler`.
- Change when: altering protected route scope or local client authentication. Add integration tests for success and failure paths.

### `model`

#### `src/main/kotlin/com/opengpt/model/OAuthToken.kt`

- `OAuthToken`: persisted credential record containing access token, refresh token, expiry epoch milliseconds, optional ChatGPT account ID, optional residency, and optional local API key.
- This class is serialized directly to disk. Field changes are storage-schema changes and should preserve backward compatibility or include migration behavior in `FileTokenStore`.

### `auth`

#### `src/main/kotlin/com/opengpt/auth/ApiKeyGenerator.kt`

- `ApiKeyGenerator`: cryptographically generates 32-byte URL-safe keys with the `sk-cla-` prefix.
- The OpenAI-like shape exists for client compatibility.

#### `src/main/kotlin/com/opengpt/auth/PkceGenerator.kt`

- `PkceCodes`: PKCE verifier/challenge value object.
- `PkceGenerator`: creates a 43-character verifier, S256 challenge, and random OAuth state using `SecureRandom`.
- Change with: OAuth URL/exchange behavior and `PkceGeneratorTest`.

#### `src/main/kotlin/com/opengpt/auth/JwtParser.kt`

- `JwtParser`: decodes JWT payloads without signature verification to extract metadata from tokens already received over the OAuth token exchange.
- Account lookup order: root `chatgpt_account_id`, namespaced OpenAI auth claim, then first organization ID.
- Residency value `no_constraint` is treated as absent.
- This is not an authentication validator; do not use it to establish trust in arbitrary bearer tokens.

#### `src/main/kotlin/com/opengpt/auth/TokenStore.kt`

- `TokenStore`: storage abstraction for token CRUD, authentication state, local key lookup, and key regeneration.
- `FileTokenStore`: synchronized JSON-file implementation.
- Uses a `ReentrantReadWriteLock`, though token reads take the write lock because loading legacy credentials may generate and persist a missing API key.
- Creates parent directories and attempts POSIX owner-only `0600` permissions; failures are ignored and parent-directory permissions are not tightened.
- Writes truncate the live file directly rather than using a temporary file plus atomic replacement, so an interrupted write can corrupt credentials.
- `save()` preserves a supplied key or generates one. `regenerateApiKey()` replaces only the local key.
- Change with: `OAuthToken`, authentication UI/filter, and `FileTokenStoreTest`.

#### `src/main/kotlin/com/opengpt/auth/OAuthService.kt`

- `PendingOAuth`: in-memory PKCE/state record for one browser login attempt.
- `PendingDeviceCode` / `DeviceCodeStart` / `DeviceCodePollResult`: device-code login state and poll outcomes.
- `TokenResponse`: OAuth token endpoint DTO; ignores unknown fields.
- `DeviceCodeUserResponse` / `DeviceCodeTokenSuccess`: Codex device-auth endpoint DTOs.
- `DeviceCodeNotEnabledException`: raised when the usercode endpoint returns 404.
- `OAuthService`: builds authorization URLs, verifies callback state, runs device-code start/poll, exchanges authorization codes (browser redirect or `{issuer}/deviceauth/callback`), refreshes tokens, extracts metadata, and persists credentials.
- Token exchange and device-auth calls are currently blocking (`Mono.block()`) within the servlet-oriented application.
- Refresh preserves the existing local API key and falls back to the old refresh token if the server returns an empty one.
- `TokenResponse.refreshToken` is non-nullable, so the fallback handles a blank value but not necessarily an omitted `refresh_token` property.
- Change with: `OAuthProperties`, `PkceGenerator`, `JwtParser`, `TokenStore`, callback handling, device-code UI, and focused OAuth tests.

#### `src/main/kotlin/com/opengpt/auth/TokenRefreshService.kt`

- `AuthenticationRequiredException`: signals missing or unusable ChatGPT credentials.
- `TokenRefreshService`: returns a token that remains valid for at least 30 seconds and coordinates callers through an atomic `CompletableFuture`.
- Current concurrency caveat: contenders start `supplyAsync` before the compare-and-set, so losing futures can still refresh and persist tokens. Do not rely on exactly one refresh request.
- Refresh runs on the default `CompletableFuture` executor.
- Change with: `OAuthService`, `CodexClient`, and exception handling. Concurrency changes require concurrent-refresh tests.

#### `src/main/kotlin/com/opengpt/auth/OAuthCallbackServer.kt`

- `OAuthCallbackServer`: lazy JDK `HttpServer` bound to loopback port `1455`.
- Parses callback query parameters, calls `OAuthService`, and serves success/error HTML.
- Error messages are HTML-escaped. The configurable public base URL in success/navigation markup is not escaped; `OAuthController` has the same interpolation behavior.
- Uses a cached thread pool, synchronized lifecycle, and `@PreDestroy` shutdown.
- The port and callback path are protocol constraints for the configured Codex OAuth client.
- Change with: `OAuthController`, `OAuthProperties`, and end-to-end OAuth behavior.

#### `src/main/kotlin/com/opengpt/auth/OAuthController.kt`

- `OAuthController`: Spring MVC controller for browser login, device-code start/poll, fallback callback, auth status, local-key regeneration, and the inline HTML/CSS/JavaScript home UI.
- Home UI offers both browser and device-code login; device-code JS polls `/auth/device/poll` until completion.
- Displays the local key on loopback to the local user.
- Change when: altering local onboarding or auth-management endpoints. Keep secrets out of `/auth/status`.

### `gateway`

#### `src/main/kotlin/com/opengpt/gateway/RequestTypeDetector.kt`

- `RequestType`: enum with `RESPONSES` and `CHAT_COMPLETIONS`.
- `RequestTypeDetector`: treats a body with `input` and no `messages` as Responses format; all other bodies are Chat Completions.
- Used only by the chat-completions endpoint for compatibility.
- Change with: `RequestTypeDetectorTest` and controller routing assumptions.

### `api`

#### `src/main/kotlin/com/opengpt/api/HealthController.kt`

- `HealthController`: public liveness endpoint. It does not check OAuth or upstream health.

#### `src/main/kotlin/com/opengpt/api/ModelsController.kt`

- `ModelsController`: exposes `ModelResolver.PUBLIC_MODELS` in OpenAI list format.
- The endpoint is protected by `LocalApiKeyFilter`.
- Change with: `ModelResolver` whenever the advertised model surface changes.

#### `src/main/kotlin/com/opengpt/api/ChatCompletionsController.kt`

- `ChatCompletionsController`: inbound `/v1/chat/completions` orchestration.
- Detects request shape, maps Chat input, selects streaming versus aggregation, sets SSE anti-buffering headers, writes/flushed frames, logs full traffic, and recognizes client disconnects.
- It should stay thin: transformation details belong in `CodexRequestMapper`, `SseProxy`, or `StreamTranslator`.
- Change with: endpoint contract tests and any affected mapper/stream tests.

#### `src/main/kotlin/com/opengpt/api/ResponsesController.kt`

- `ResponsesController`: inbound `/v1/responses` orchestration.
- Sets SSE headers, writes prepared upstream events, logs traffic, and handles disconnects.
- Unlike the chat controller, it currently always streams.

#### `src/main/kotlin/com/opengpt/api/ApiExceptionHandler.kt`

- `ApiExceptionHandler`: global controller advice for OpenAI-shaped errors and normal client-disconnect handling.
- `CodexBackendException` is intentionally sanitized to a generic 502 response.
- Errors raised after an SSE response has started may not be convertible to a normal JSON error; preserve stream-local handling in controllers/proxy.

### `codex`

#### `src/main/kotlin/com/opengpt/codex/CallIdNormalizer.kt`

- `CallIdNormalizer`: deterministically hashes tool call IDs longer than Codex's 64-character limit.
- The same normalization must be applied to calls, results, stream events, and reasoning cache keys or tool history will become unpaired.

#### `src/main/kotlin/com/opengpt/codex/ModelResolver.kt`

- `ReasoningEffortResolver`: extracts and normalizes effort from `reasoning.effort`, Cursor-style `cursor_model_params`, `reasoning_effort`, or `reasoningEffort`, in that precedence order. `extra high` normalizes to `xhigh`; `max` remains distinct for Astra and normalizes to `xhigh` for older models.
- `ModelResolver`: normalizes requested model IDs and extracts recognized reasoning-effort suffixes before generic model compatibility checks.
- `ModelResolver.Resolved`: resolved upstream model plus optional effort derived from the model suffix.
- `BASE_ALLOWED`: known compatible base models, including `gpt-6-astra`.
- `PUBLIC_MODELS`: base models, GPT-looking effort aliases for compatible clients, and Cursor-safe `cla-{astra|sol|terra|luna}-*` aliases, advertised by `/v1/models`.
- `DEFAULT`: fallback model, currently `gpt-5.4`.
- Astra supports `low`, `medium`, `high`, `xhigh`, and `max`, defaults to `high`, and maps `none`/`minimal` to that default because Astra does not support those levels. Account entitlement is still enforced upstream.
- Effort aliases such as `gpt-6-astra-max`, `gpt-5.6-sol-medium`, and `gpt-5.6-terra-high` are stripped to the base model and converted to `reasoning.effort`.
- Cursor-safe aliases such as `cla-astra-max` and `cla-sol-low` are explicitly mapped to their Codex model and effort. Their non-GPT-looking names prevent affected Cursor versions from canonicalizing the effort suffix away before making the HTTP request.
- Versioned GPT IDs whose parsed numeric version is greater than 5.4 pass compatibility checks except plain `gpt-5.6` and `-pro` variants. The implementation uses `String.toDouble()`, so semantic versions such as `5.10` would be interpreted as `5.1`.
- Default effort policy: Astra -> `high`; 5.6/sol/terra/luna -> `xhigh`; 5.5/5.4 -> `high`; otherwise `medium`.
- Cursor's Override OpenAI Base URL path currently drops its UI effort selector and can turn a selected `gpt-5.6-sol-low` alias back into `gpt-5.6-sol`. A bare GPT-5.6 request therefore reaches the fallback; affected Cursor users must select a `cla-*` alias for a reliable per-request choice.
- Model discovery is advisory: unknown/incompatible IDs are generally lowercased and forwarded rather than rejected.
- The `gpt-5.4` default is duplicated here and as a literal in `CodexRequestMapper`; keep them aligned.
- Change with: `ModelsController`, `SseProxy`, any user-facing model documentation, and `ModelResolverTest`.

#### `src/main/kotlin/com/opengpt/codex/ReasoningStateCache.kt`

- `ReasoningStateCache`: in-memory two-hour cache of encrypted reasoning items.
- Private `Entry`: immutable item snapshot plus expiry time.
- Stores by normalized call ID and/or hash of trimmed assistant text; returns deep copies.
- Change with: mapper injection, stream capture, call-ID behavior, and `ReasoningStateCacheTest`.

#### `src/main/kotlin/com/opengpt/codex/CursorImageRecovery.kt`

- `CursorImageRecovery`: extracts path hints, detects black placeholder images, searches for originals, and creates replacement data URLs.
- `CursorImageRecovery.ResolvedImage`: recovered data URL plus a logging-only source label.
- Search locations include the hinted file's directory and `~/Pictures/Screenshots`, `~/Pictures`, `~/Downloads`, and `~/Desktop`.
- Image darkness is sampled rather than scanning every pixel.
- Change with: multimodal mapping in `CodexRequestMapper` and `CursorImageRecoveryTest`.

#### `src/main/kotlin/com/opengpt/codex/CodexRequestMapper.kt`

- `CodexRequestMapper`: central Chat Completions -> Responses lowering layer.
- Handles instructions, text/image parts, assistant history, normal/custom calls, tool results, image-bearing tool results, tool definitions/choice, sampling fields, reasoning options, and `store: false`.
- Unknown message roles are handled as user messages. Common Chat Completions fields such as token limits, `stop`, `n`, `seed`, `response_format`, penalties, and log probabilities are not forwarded. `parallel_tool_calls` is forwarded when present; when tools exist and the client omits it, the mapper defaults it to `true`.
- Restores encrypted reasoning before the associated assistant history.
- Missing reasoning cache entries are expected for imported history, expired entries, and history created before the current process. Those misses are debug-level only; successful restoration remains visible at info level.
- Promotes function-shaped custom tool history back to Codex custom calls.
- This is a high-coupling compatibility file. Changes normally require fixtures in `CodexRequestMapperTest` and corresponding stream round-trip checks.

#### `src/main/kotlin/com/opengpt/codex/CodexClient.kt`

- `CodexClient`: authenticated upstream HTTP/SSE client.
- Adds OAuth bearer token, JSON/SSE content headers, configured `User-Agent` and `originator`, optional `ChatGPT-Account-Id`, and optional residency.
- Returns raw `Flux<DataBuffer>` so SSE framing can be decoded by `SseProxy`.
- `CodexBackendException`: carries upstream status and body for internal logging/error conversion.
- Refresh is expiry-driven before the request. An upstream 401 does not trigger refresh-and-retry.
- Change with: auth/token refresh, Codex headers, `AppConfig`, and `CodexClientWireMockTest`.

### `streaming`

#### `src/main/kotlin/com/opengpt/streaming/StreamTranslator.kt`

- `StreamTranslator`: factory for per-request translation state.
- `StreamTranslator.Session`: state machine translating ordered Codex Responses events to Chat Completions chunks.
- Private `PendingCustomTool`: buffered custom-tool name, call ID, dense index, input, and emission state.
- Private `PendingFunctionTool`: same buffering for normal function tools so empty `{}` arguments never reach Cursor.
- `SuppressedEmptyTool`: metadata for empty calls withheld from the client; `SseProxy` can transparently retry Codex once with a synthetic tool error output.
- A session owns completion ID, role bootstrap state, text accumulation, usage, finish reason, tool index maps, completed call IDs, and captured reasoning.
- Handles text deltas, buffered function calls, buffered custom calls, completion/failure/incomplete events, usage normalization, and reasoning capture.
- Current failure semantic: failed, error, and incomplete terminal events are lowered to normal finish chunks instead of an error indication.
- There is no terminal-event guard; multiple completion-like events can emit duplicate finish/usage chunks.
- Function and custom tool arguments are buffered until done and emitted as one Chat Completions tool_calls chunk.
- Unknown events are intentionally ignored.
- Session instances are request-scoped values created manually; `StreamTranslator` itself is a singleton component.
- Change with: `SseProxy`, `CodexRequestMapper` round trips, and `StreamTranslatorTest`.

#### `src/main/kotlin/com/opengpt/streaming/SseProxy.kt`

- `SseProxy`: central stream orchestrator.
- Prepares Responses requests, invokes `CodexClient`, decodes upstream SSE, translates Chat streams, emits keepalives and `[DONE]`, aggregates non-streaming text responses, logs usage, and saves reasoning state.
- Private `SseAccumulator`: mutable line/block accumulator that joins `data:` lines and filters upstream `[DONE]`.
- The translated stream is shared because both output and keepalive completion tracking subscribe to it.
- `DataBuffer` instances are explicitly released after decoding; preserve that to avoid leaks.
- Parser limitations: UTF-8 is decoded one `DataBuffer` at a time, final events without a blank-line terminator are dropped, and `data:` whitespace is normalized.
- The proxy retains the complete raw upstream stream for logging; controllers separately retain the complete downstream stream. Streaming therefore uses memory proportional to total traffic despite immediate client flushing.
- Change with: controllers, model policy, stream translator, reasoning cache, traffic logging, and stream-level tests.

### `debug`

#### `src/main/kotlin/com/opengpt/debug/TrafficDebugLog.kt`

- `TrafficDebugLog`: synchronized append-only plain-text log with process-local exchange IDs and timestamps.
- Logs client requests, prepared Codex requests, raw Codex SSE, client SSE/JSON, and stack traces.
- Initializes eagerly and always writes; there is currently no enable/disable or redaction setting.
- Treat this file's output as sensitive because it can contain prompts, responses, images, tool arguments/results, reasoning ciphertext, file contents, and local paths.
- Has no rotation, retention limit, size limit, or explicit permission hardening. Synchronous final writes and the full-stream buffers used to produce them can be expensive.

### `util`

#### `src/main/kotlin/com/opengpt/util/JsonNodes.kt`

- `JsonNodes`: Jackson 3-safe text helpers.
- `text`/`textAt`: return scalar text/defaults and avoid accidentally treating objects/arrays as useful text.
- `jsonText`/`jsonTextAt`: preserve objects/arrays by serializing them, which is important for function arguments and tool output.
- Reuse these helpers at compatibility boundaries instead of scattering subtly different Jackson coercion behavior.

## 7. Configuration and local state map

### `src/main/resources/application.yml`

- `server.port`: main Spring server; default `5080`.
- `server.address`: loopback bind; default `127.0.0.1`.
- `spring.main.web-application-type`: explicitly `servlet`.
- `spring.application.name`: Spring application identity; default `opengpt`.
- `adapter.storage-path`: credentials/local-key JSON; default `~/.opengpt/auth.json`.
- `adapter.public-base-url`: base shown in UI; can be a tunnel URL, but it does not change the server bind.
- `adapter.traffic-log-path`: unredacted traffic log; default `~/.opengpt/traffic.log`.
- `openai.oauth.client-id`: Codex OAuth public client ID.
- `openai.oauth.issuer`: OpenAI OAuth issuer.
- `openai.oauth.callback-url`: registered redirect URI; keep aligned with callback server and normally fixed to port `1455`.
- `openai.oauth.callback-port`: JDK callback-listener port.
- `openai.oauth.scope`: includes `offline_access` for refresh tokens.
- `openai.oauth.originator`: identity included in authorization URL.
- `codex.endpoint`: upstream Responses endpoint.
- `codex.user-agent`: outbound user agent.
- `codex.originator`: outbound Codex originator header.
- `logging.level.root`: root application log threshold; default `INFO`.
- `logging.level.com.opengpt`: project-package log threshold; default `INFO`.

### Runtime files outside the repository

- `~/.opengpt/auth.json`: OAuth access/refresh tokens, expiry, account metadata, residency, and local API key. Never commit or log this file.
- `~/.opengpt/traffic.log`: unredacted traffic. Never commit it; remove or protect it before sharing diagnostics.

### Build and editor artifacts

- `target/`: Maven-generated classes, test classes, generated sources, reports, and packaged JARs. Do not edit.
- `.idea/` and `.kotlin/`: local IDE/tool metadata. Do not use as architectural sources.
- `HELP.md`: generated/stale Spring help containing Gradle references. `pom.xml` and `README.md` are authoritative for the Maven build.

## 8. Build and dependency map

### `pom.xml`

- Parent: Spring Boot `4.0.0`.
- Kotlin: `2.2.21`.
- Java: `21`.
- Runtime starters:
  - validation;
  - WebFlux/Reactor Netty for outbound reactive HTTP;
  - WebMVC for the servlet server/controllers.
- Kotlin/Reactor:
  - reactor Kotlin extensions;
  - Kotlin reflection and standard library;
  - coroutines/Reactor integration.
- JSON: Jackson Kotlin module using Spring Boot 4/Jackson 3 `tools.jackson.*` APIs. OAuth annotations still use their compatible annotation package.
- Tests:
  - Spring validation/WebFlux/WebMVC test starters;
  - Kotlin JUnit 5;
  - coroutine test support;
  - WireMock standalone.
- Build plugins:
  - Spring Boot Maven plugin;
  - Kotlin Maven plugin with Spring all-open support and strict JSR-305 handling.

### `.mvn/maven.config` and `.mvn/maven-central-settings.xml`

- Force this project to resolve dependencies/plugins directly from Maven Central.
- This avoids incomplete global Maven/Nexus mirrors, particularly for Spring Boot 4 artifacts.

### Standard commands

```bash
mvn test
mvn spring-boot:run
mvn -DskipTests package
java -jar target/opengpt-0.1.0-SNAPSHOT.jar
```

There is no Maven wrapper in the current project. Use Maven 3.9+ from the environment.

## 9. Test file map

### Context and endpoint tests

- `src/test/kotlin/com/opengpt/OpengptApplicationTests.kt`
  - Verifies that the full Spring context loads.
- `src/test/kotlin/com/opengpt/HealthAndModelsTest.kt`
  - Seeds a local key, verifies public health, and verifies authenticated `/v1/models` shape.

### Authentication tests

- `src/test/kotlin/com/opengpt/auth/ApiKeyGeneratorTest.kt`
  - Protects local-key prefix and nontrivial length.
- `src/test/kotlin/com/opengpt/auth/FileTokenStoreTest.kt`
  - Protects save/load, automatic key generation, and clear behavior using a temporary directory.
- `src/test/kotlin/com/opengpt/auth/JwtParserTest.kt`
  - Protects root/namespaced/organization account lookup and residency extraction.
- `src/test/kotlin/com/opengpt/auth/PkceGeneratorTest.kt`
  - Protects verifier length and S256 computation. It calls `randomState()` and manually assembles an illustrative URL, but it does not exercise `OAuthService.buildAuthorizationUrl()`, prove state uniqueness/shape, or verify production's default `originator=opencode`.
- `src/test/kotlin/com/opengpt/auth/DeviceCodeAuthWireMockTest.kt`
  - Protects device-code usercode start, 404-not-enabled handling, pending→authorized poll, and token exchange against `{issuer}/deviceauth/callback`.

### Gateway and Codex tests

- `src/test/kotlin/com/opengpt/gateway/RequestTypeDetectorTest.kt`
  - Protects Responses detection and Chat precedence when both shapes appear.
- `src/test/kotlin/com/opengpt/codex/CallIdNormalizerTest.kt`
  - Protects unchanged short IDs and deterministic <=64-character long-ID hashing.
- `src/test/kotlin/com/opengpt/codex/ModelResolverTest.kt`
  - Protects model pass-through, generic effort-suffix extraction, Cursor parameter extraction/precedence, advertised effort aliases, compatibility, and effort defaults.
- `src/test/kotlin/com/opengpt/codex/ReasoningStateCacheTest.kt`
  - Protects encrypted-reasoning storage by call ID/text and rejection of unusable items.
- `src/test/kotlin/com/opengpt/codex/CursorImageRecoveryTest.kt`
  - Protects `<image_files>` path extraction and black-placeholder recovery/naming heuristics.
- `src/test/kotlin/com/opengpt/codex/CodexRequestMapperTest.kt`
  - Protects text/image mapping, instructions, reasoning effort, normal/custom tool history, ApplyPatch promotion, call-ID pairing, and tool-definition normalization.
- `src/test/kotlin/com/opengpt/codex/CodexClientWireMockTest.kt`
  - Protects the upstream path, OAuth/account/residency/originator headers, and raw SSE body delivery.

### Streaming tests

- `src/test/kotlin/com/opengpt/streaming/StreamTranslatorTest.kt`
  - Protects role/text chunks, dense tool indexes, argument deltas, usage conversion, custom ApplyPatch buffering/emission, done-event variants, reasoning capture, and unknown-event tolerance.

### Test profile

- `src/test/resources/application-test.yml`
  - Redirects credential and traffic files into the system temporary directory only when the `test` profile is active.
  - `HealthAndModelsTest` activates this profile. `OpengptApplicationTests` does not, so its context startup currently appends to the real default `~/.opengpt/traffic.log`.

### Important current coverage gaps

There are no focused tests for:

- full browser OAuth service/callback HTTP exchanges;
- device-code controller/UI integration beyond `DeviceCodeAuthWireMockTest`;
- correct concurrent token-refresh deduplication;
- invalid/missing local API-key responses;
- all focused `SseProxy` behavior, including body preparation/defaults, arbitrary `DataBuffer` boundaries, keepalive timing/stream sharing, encrypted-reasoning inclusion, and ordinary aggregation;
- non-streaming tool-call aggregation;
- `ResponsesController` and `ChatCompletionsController` end-to-end SSE;
- focused `ApiExceptionHandler` behavior;
- upstream non-2xx `CodexClient` handling and its sanitized 502 conversion;
- `response.failed`, `error`, `response.incomplete`, and `response.done` translation semantics;
- legacy token-file upgrade, supplied-key preservation, key regeneration, and attempted `0600` permissions;
- traffic-log redaction/rotation because neither exists yet;
- API exception behavior after an SSE response has started.

Add targeted tests in these areas before making risky changes.

## 10. Change-impact guide

Use this section to find the smallest coherent edit set.

### Add, remove, or rename a model

Inspect/change:

- `codex/ModelResolver.kt`: compatibility, advertised list, aliases, and default effort.
- `api/ModelsController.kt`: normally no logic change, but verify output.
- `codex/ModelResolverTest.kt`.
- `HealthAndModelsTest.kt` if ordering/shape assumptions change.
- `README.md` and this file if user-facing model behavior changes.

### Change Chat Completions request compatibility

Inspect/change:

- `codex/CodexRequestMapper.kt`.
- `util/JsonNodes.kt` if JSON coercion is involved.
- `codex/CallIdNormalizer.kt`, `ReasoningStateCache.kt`, or `CursorImageRecovery.kt` for those domains.
- `codex/CodexRequestMapperTest.kt`.
- `streaming/StreamTranslatorTest.kt` for round-trip symmetry.

### Add or change a Codex SSE event

Inspect/change:

- `streaming/StreamTranslator.kt` for Chat translation/state.
- `streaming/SseProxy.kt` for framing, lifecycle, and completion behavior.
- `streaming/StreamTranslatorTest.kt`.
- `codex/CodexRequestMapper.kt` if the event represents history that must be sent back later.

### Change tool/custom-tool behavior

Inspect/change together:

- `codex/CodexRequestMapper.kt`: client history -> Codex items.
- `streaming/StreamTranslator.kt`: Codex events -> client tool calls.
- `codex/CallIdNormalizer.kt`: stable ID pairing.
- `codex/ReasoningStateCache.kt`: association with tool turns.
- mapper and translator tests.

Always test at least one complete sequence: declaration -> streamed call -> client tool result -> next mapped Codex request.

### Change OAuth settings or login

Inspect/change:

- `config/ApplicationProperties.kt`.
- `src/main/resources/application.yml`.
- `auth/PkceGenerator.kt`.
- `auth/OAuthService.kt` (browser and device-code flows).
- `auth/OAuthCallbackServer.kt` for browser callback only.
- `auth/OAuthController.kt` including `/auth/device/*` and home UI.
- `auth/JwtParser.kt` for changed token claims.
- auth tests (`DeviceCodeAuthWireMockTest` for device code) and README setup instructions.

Do not casually change callback port/path or client ID. Device-code exchange must keep using `{issuer}/deviceauth/callback`.

### Change token storage

Inspect/change:

- `model/OAuthToken.kt`.
- `auth/TokenStore.kt`.
- `auth/OAuthService.kt` and `TokenRefreshService.kt`.
- `config/LocalApiKeyFilter.kt` and auth UI if local-key semantics change.
- `FileTokenStoreTest.kt` plus backward-compatibility fixtures.

### Change upstream HTTP behavior

Inspect/change:

- `config/AppConfig.kt`: timeouts, connector, codec limit.
- `codex/CodexClient.kt`: URL, headers, status handling, buffers.
- `auth/OAuthService.kt`: the same builder also handles OAuth token requests.
- `CodexClientWireMockTest.kt`.

### Change image support

Inspect/change:

- `codex/CursorImageRecovery.kt`.
- image branches in `codex/CodexRequestMapper.kt`, including image-bearing tool results.
- `CursorImageRecoveryTest.kt` and `CodexRequestMapperTest.kt`.
- memory/privacy implications of embedding data URLs and logging request bodies.

### Change API routes or authentication scope

Inspect/change:

- controller mapping(s) under `api`.
- `config/LocalApiKeyFilter.kt` path matching.
- `api/ApiExceptionHandler.kt`.
- endpoint integration tests.
- README endpoint/client instructions.

### Make traffic logging optional, safer, or structured

Inspect/change:

- `debug/TrafficDebugLog.kt`.
- `config/ApplicationProperties.kt`.
- `src/main/resources/application.yml` and test profile.
- both stream controllers and `streaming/SseProxy.kt`.
- privacy documentation and new tests.

## 11. Invariants coding agents must preserve

1. Bind local servers to loopback unless the user explicitly accepts the security consequences of external binding.
2. Keep the OAuth callback URI aligned with the registered port-1455 Codex redirect.
3. Never send the local `sk-cla-...` key upstream.
4. Never expose OAuth access/refresh tokens through API responses or logs.
5. Preserve the local API key during OAuth token refresh.
6. Apply identical call-ID normalization to calls and their outputs.
7. Keep Responses item IDs/output indexes distinct from dense Chat Completions tool indexes.
8. Keep custom-tool translation symmetric between outgoing stream and next-turn request history.
9. Preserve raw ApplyPatch freeform input unless a tested client contract requires another shape.
10. Request, capture, cache, and restore encrypted reasoning as one coherent feature.
11. Release every consumed `DataBuffer`.
12. Preserve SSE ordering and flush behavior; do not parallelize event translation.
13. Keep no-op keepalives valid Chat Completions chunks because comments can be stripped by proxies.
14. Treat disconnect exceptions as expected stream lifecycle events, not server failures.
15. Treat credential and traffic files as sensitive local state.
16. Maintain the single-user assumption explicitly or redesign pending OAuth and reasoning caches for isolation.
17. If tunneling, keep the UI and `/auth/*` private or add independent access control; `/v1` key authentication does not protect them.
18. Do not broaden image recovery without addressing client-controlled local-file reads and upload limits.

## 12. Coding and verification guidance

- Prefer constructor injection, which is the existing Spring/Kotlin style.
- Keep per-stream mutable state inside `StreamTranslator.Session`, not singleton component fields.
- Use immutable data classes for configuration and persisted records.
- Use `JsonNodes` helpers when handling permissive client JSON.
- Deep-copy mutable Jackson nodes before caching or reusing them across requests.
- Keep compatibility decisions documented near the code and protected by regression tests.
- Do not edit `target`, `.idea`, or `.kotlin`.
- Do not rely on `HELP.md` for build instructions.
- Avoid real OAuth/Codex calls in tests; use temporary files, fixtures, and WireMock.

Verification proportional to the change:

1. Run focused tests while iterating, for example:

```bash
mvn -Dtest=CodexRequestMapperTest test
mvn -Dtest=StreamTranslatorTest test
```

2. Run the complete suite before handoff:

```bash
mvn test
```

3. For stream contract changes, manually verify:
   - role bootstrap;
   - ordered content/tool chunks;
   - dense tool indexes;
   - finish reason;
   - usage-only chunk;
   - `[DONE]`;
   - next-turn tool history/reasoning restoration.

4. For OAuth or storage changes, test with a temporary `adapter.storage-path`. Never use or commit a real credentials fixture.

## 13. Known risks and limitations

- Using ChatGPT subscription credentials outside official clients may conflict with OpenAI terms.
- A public HTTPS tunnel makes a loopback-oriented service externally reachable. The local API key becomes the main application-level protection.
- Only `/v1/*` is protected by that key. The UI displays it, `/auth/status` exposes account metadata, and `/auth/regenerate-key` is unauthenticated and lacks CSRF protection; publishing the whole service can defeat the intended boundary.
- Traffic logging is always enabled and unredacted.
- Traffic logging has no rotation/size limit and causes complete upstream and downstream streams to be retained in memory.
- The home page intentionally displays the local key to anyone who can reach it; this is safe only under the local single-user boundary.
- OAuth pending state and reasoning cache are process-local and lost on restart.
- OAuth pending state does not expire, a second login replaces it, and a wrong-state callback consumes it.
- Reasoning cache entries are not tenant/conversation isolated.
- Reasoning cache has no entry/byte cap and is pruned only during cache operations.
- The OAuth callback server uses an unbounded cached thread pool.
- Its separately created executor is not explicitly shut down, and the listener remains open after successful login until application shutdown.
- Token refresh uses the default `CompletableFuture` executor and does not strictly deduplicate simultaneous refresh requests.
- Non-streaming Chat Completions do not reconstruct tool calls.
- Chat translation currently masks upstream failed/incomplete terminal events as normal finish chunks.
- Responses proxying is streaming-only and not byte-for-byte transparent.
- Health is liveness only; it does not verify credentials or upstream availability.
- Image recovery can read and upload client-selected local files, has no file-size limit, and performs synchronous image decoding.
- Long streams hold a servlet worker and can also block Reactor emission threads while writing to a slow client.
- The default context-load test can write an adapter-start marker to the real user traffic log because it does not activate the test profile.
- The current test suite has limited controller-level SSE and OAuth integration coverage.
