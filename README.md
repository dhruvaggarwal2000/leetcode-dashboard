# LeetCode Dashboard

A personal LeetCode tracking dashboard. The backend is a Spring Boot REST API; the frontend is a React SPA. There is no authentication — the active user is passed on every request as a plain string header (`X-User-Id`), which the frontend reads from `localStorage` key `lc_username`. Designed for single-user, local-only use.

## Features

- **Problem tracker** with topic, pattern, confidence (1–5), and free-form notes per problem.
- **Spaced-repetition review queue** — problems with `confidence < 3` surface for review.
- **Heatmaps** — total submission heatmap and "new vs. repeated" problem-activity heatmap, computed from your local submission history.
- **LeetCode sync** in two modes: anonymous (recent solves) and authenticated (full history via session cookie).
- **Cross-user leaderboard** of LeetCode profiles tracked in the local DB.
- **Practice Coach chatbot** with two pluggable backends (toggleable via config):
  - **`cli` mode** (default) — shells out to the locally installed `claude` CLI; uses your existing Claude Code subscription, no API key needed.
  - **`api` mode** — calls the Anthropic Messages API directly with an API key.

## Stack

- **Backend**: Spring Boot 3, Java 17, Spring Web (MVC) + WebFlux (for upstream calls), Spring Data JPA, Liquibase
- **Frontend**: React 18, Vite, React Router v6, Recharts, react-markdown
- **Database**: PostgreSQL 16 via Docker Compose
- **Chat**: pluggable provider — local `claude` CLI **or** Anthropic Messages API (toggle: `app.chat.provider=cli|api`)

## Quick start

```bash
# 1. Create your env file
cp .env.example .env
# Edit .env: set POSTGRES_USER, POSTGRES_PASSWORD (required) and ANTHROPIC_API_KEY (only if using chat 'api' mode)

# 2. Start PostgreSQL
docker compose up -d

# 3. Start backend + frontend together
./start.sh           # macOS / Linux
start.bat            # Windows

# 4. Open http://localhost:5173, enter your LeetCode username on the Dashboard
```

The first launch runs Liquibase to create all tables. Click **Sync** on the Dashboard to pull your LeetCode profile + solved problems.

## Commands

### Running the full stack

```bash
./start.sh           # starts both backend (port 8080) and frontend (port 5173)
./stop.sh            # stops both
./restart.sh         # restarts both
```

Windows equivalents: `start.bat`, `stop.bat`, `restart.bat`.

### Backend (Spring Boot / Maven) — run from `backend/`

```bash
mvn spring-boot:run                     # start backend
mvn compile                             # compile only
mvn test                                # run all tests
mvn test -Dtest=ClassName#methodName    # run a single test
```

Java 17 is required. On Apple Silicon, `start.sh` sets `JAVA_HOME` to `/opt/homebrew/opt/openjdk@17/...`.

### Frontend (React / Vite) — run from `frontend/`

```bash
npm run dev          # dev server on port 5173
npm run build        # production build
npm run preview      # preview the production build
```

### Database (PostgreSQL via Docker Compose)

```bash
docker compose up -d         # start PostgreSQL (data persisted at ./postgres-data/)
docker compose down          # stop
```

Connect via psql: `docker exec -it leetcode-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"`
Or use any GUI tool (TablePlus, DBeaver) at `localhost:5432`. Credentials come from `.env`.

Schema is managed by **Liquibase** — changesets live in `backend/src/main/resources/db/changelog/changes/`. To add a schema change, create a new `00N-description.xml` and include it in `db.changelog-master.xml`. Never edit existing changesets.

## Configuration

### Environment variables (`.env`)

| Variable | Required | Purpose |
|---|---|---|
| `POSTGRES_USER` | yes | Postgres user; injected into both `docker-compose.yml` and `application.yml`. |
| `POSTGRES_PASSWORD` | yes | Postgres password (the compose file refuses to start without it). |
| `POSTGRES_DB` | no (default `leetcode_db`) | Database name. |
| `ANTHROPIC_API_KEY` | only for chat `api` mode | Read by `app.chat.anthropic.api-key` in `application.yml`. |

### Application properties (`backend/src/main/resources/application.yml`)

| Property | Default | Purpose |
|---|---|---|
| `app.chat.enabled` | `true` | Master toggle for the Practice Coach feature. When `false`, `ChatController` and both chat services are not registered as Spring beans; the Chat tab is hidden in the UI; `/api/chat/**` returns 404. |
| `app.chat.provider` | `cli` | `cli` → local `claude` CLI subprocess. `api` → Anthropic Messages API. |
| `app.chat.cli.model` | `sonnet` | Passed to `claude --model` in `cli` mode. Accepts an alias (`sonnet`, `opus`, `haiku`) or a full model ID. |
| `app.chat.anthropic.api-key` | `${ANTHROPIC_API_KEY:}` | API key (only used in `api` mode). |
| `app.chat.anthropic.model` | `claude-sonnet-4-6` | Model ID for `api` mode. |
| `app.chat.anthropic.max-tokens` | `4096` | Per-turn max output tokens. |
| `app.chat.anthropic.base-url` | `https://api.anthropic.com` | Override for proxies/testing. |

The conditional wiring uses Spring's `@ConditionalOnProperty` / `@ConditionalOnExpression` — toggling these requires a backend restart. The UI reads the live values from `GET /api/features` on every page load.

---

## Architecture

### Backend (`backend/src/main/java/com/leetcode/dashboard/`)

**Controllers / services / entities:**

| Layer | Class | Responsibility |
|---|---|---|
| Controller | `ProblemController` (`/api/problems`) | Read/update/delete for problems, review queue, similar-problem lookup. **No create endpoint** — `Problem` rows are inserted exclusively by `StatsController.syncSolvedProblems`. |
| Controller | `StatsController` | Stats, heatmaps, leaderboard, LeetCode profile proxy, full sync. |
| Controller | `ChatController` (`/api/chat`) | SSE-streamed chat with Claude (practice coach). Delegates to a `ChatProvider` bean; gated by `app.chat.enabled`. |
| Controller | `FeaturesController` (`/api/features`) | Always-on feature-flag readout: `{ chatEnabled, chatProvider }`. |
| Service | `ProblemService` | Business logic for problems; deduplication on read. |
| Service | `LeetCodeService` | WebFlux calls to LeetCode's GraphQL API. |
| Service | `ChatProvider` (interface) | `streamReply`, `clearSession`. Either of the two beans below is wired in, never both. |
| Service | `ClaudeSessionService` (`provider=cli`) | Spawns `claude -p` via `ProcessBuilder`, parses NDJSON stream events, pins session IDs per user for multi-turn memory. |
| Service | `AnthropicApiChatService` (`provider=api`) | Streams from the Anthropic Messages API via WebClient; keeps per-user conversation history in memory. |
| Entity | `Problem` | Core record (number, title, difficulty, topic, pattern, confidence, dates, submission metadata, userId). |
| Entity | `Account` | Per-user LeetCode profile snapshot (solved counts, ranking, acceptance rate). |
| Entity | `Submission` | Log of accepted submission events from authenticated sync only. |

**Key design decisions:**

- **Sync is upsert-in-place; read-time dedup is defensive** (`StatsController.syncSolvedProblems` + `ProblemService.getAll`).
  - *Write path:* before saving, the controller builds `existingByNum = problemRepository.findByUserId(...)` keyed on `leetcode_number`. For each incoming problem, if a row exists for that number it's mutated and re-saved (preserving user-edited fields like `confidence` / `notes` / `due_date`); otherwise a new row is inserted. So the Problem table normally holds one row per `(user_id, leetcode_number)`.
  - *Schema note:* this is enforced by the sync logic, not the DB — `(user_id, leetcode_number)` is an index, not a unique constraint. Manual inserts or legacy data could violate it.
  - *Read path:* `ProblemService.getAll` still dedups defensively, keyed on `leetcode_number` (fallback `title`). The "winner" is picked per request — `confidence > 0` beats no confidence, otherwise the more recent `solved_date` wins. Topic and pattern from the loser are merged into the winner. This is a safety net for legacy duplicates, not a load-bearing feature of the current sync flow.

- **The LeetCode username is the user identity — no separate auth or user table.**
  - *Why:* single-user trust model — there is no signup, no password, no session.
  - *How:* the frontend stores the active username in `localStorage` under `lc_username`; an axios interceptor forwards it on every request as the `X-User-Id` header. `Account.userId` is the primary key; `Problem.userId` and `Submission.userId` are plain `VARCHAR` columns scoping every query — there is **no DB-level foreign key constraint** linking them to `Account`, just convention.
  - *Consequence:* the app must not be exposed to other users — anyone hitting the backend can claim to be anyone.

- **Two sync modes, with different fidelity.**
  - *Why two:* anonymous mode lets a user try the app immediately (no LeetCode login flow inside this app); authenticated mode is needed for the heatmap because LeetCode does not expose full submission history without auth.
  - *Anonymous:* no session header. Uses the public `recentAcSubmissionList` GraphQL query (capped at `limit`, default 500). Writes only to `Problem` — there are no submission events to record.
  - *Authenticated:* `X-LC-Session` (the user's `LEETCODE_SESSION` cookie). Fetches the full paginated submission list via `submissionList` (CSRF token fetched first). Writes to both `Problem` and `Submission`.
  - *Cleanup:* after a successful authenticated sync, orphan rows (`leetcode_number IS NOT NULL AND last_submission_id IS NULL`) are deleted — these are anonymous-mode leftovers now superseded by authenticated data.

- **Per-slug enrichment cache during sync** (`LeetCodeService.getAllSolvedProblems`).
  - *Problem:* a user with N accepted submissions of one problem would otherwise trigger N `question(slug)` GraphQL calls to fetch the same difficulty/topic/number metadata.
  - *Decision:* cache the enrichment by slug on the first hit and fan it out to every submission of that slug. The controller still receives one `Problem` object per submission event (so `Submission` can be populated 1:1), but LeetCode is hit at most once per unique problem.

- **`Submission.id` is the LeetCode submission ID, not a generated key.**
  - *Why:* a natural key makes re-syncing idempotent without an `INSERT ... ON CONFLICT` dance — the set of known IDs is loaded into memory once at the start of the sync, and any row already in that set is skipped on write.
  - *Implication:* the sync always re-fetches the full submission history from LeetCode (no early-stop pagination). A previous sync that died mid-pagination would otherwise leave permanent gaps that a "stop when we see a known one" loop would never fix.

- **Pattern data comes from a bundled JSON file, not LeetCode.**
  - *Why:* LeetCode's API exposes `topicTags` (Array, Tree, Hash Table…) but not the higher-level "pattern" classifications a learner usually thinks in (Sliding Window, Two Pointers, Monotonic Stack…).
  - *How:* `tuf-mapping.json` (derived from takeUforward's roadmap, hence the name) maps title slugs → `{topic, pattern}`. Topic is sourced from LeetCode's `topicTags[0]` as the primary; pattern comes **only** from this file.
  - *Note:* the same JSON is duplicated in `frontend/src/` so the UI can fill in missing patterns client-side if the backend value is null.

- **Schema is owned by Liquibase, not Hibernate** (`spring.jpa.hibernate.ddl-auto=none`).
  - *Why:* schema evolution should be explicit and reviewable, not a side effect of changing a `@Column` annotation.
  - *How:* Hibernate never creates or alters tables. All schema lives in `backend/src/main/resources/db/changelog/changes/00N-*.xml`, applied at boot. Existing changesets are immutable — schema changes must be added as new files and included in `db.changelog-master.xml`.
  - *Persistence:* data survives container restarts via the `./postgres-data/` Docker bind mount.

- **Heatmap is served entirely from the local `Submission` table — never hits LeetCode at query time.**
  - *Why:* heatmap rendering needs per-day counts across an entire year. Doing that via GraphQL on every page visit would be slow and rate-limit-prone. Once submissions are synced, all the data needed is local.
  - *`lcActivity`* (total submissions per day): one aggregate query (`getCountByDay`).
  - *`problemActivity`* (new vs. repeated per day): one query (`getActivityByDay`). A day is "new" for a problem if `submission_date = MIN(submission_date)` over all-time for that `(user_id, leetcode_number)` pair — so a problem solved once in 2025 and again in 2026 contributes "new" to the 2025 day and "repeated" to the 2026 day.
  - *Frontend cache:* all visited years are stored in `useRef`. Past years are immutable, so only the current year ever needs re-fetching; the ↻ button clears just that one year.

- **`Account` and `Submission` serve different purposes — don't conflate them.**
  - *`Account`:* a snapshot of LeetCode's *profile* counts (`totalSolved`, `ranking`, `acceptanceRate`, last-synced timestamp), refreshed on every sync. Powers the leaderboard and the "what does LeetCode say I've solved" header on the dashboard.
  - *`Submission`:* the local *event log* of individual accepted submissions, used exclusively for heatmaps.
  - *Why it matters:* the `/api/stats` numbers shown in the body of the dashboard come from the `Problem` table — i.e. what's actually been imported — not from `Account`. This is deliberate: it makes the discrepancy between "LeetCode says X" and "we have data on Y" visible, instead of papering over a half-finished sync.

### Chat (Practice Coach)

A built-in chatbot ("Practice Coach") that talks through LeetCode problems. The `ChatController` depends on a `ChatProvider` interface; exactly one implementation is wired into Spring based on `app.chat.provider`.

#### Provider: `cli` (default) — `ClaudeSessionService`

Shells out to the locally installed `claude` CLI, so it runs on the user's existing Claude Code subscription with no API key or per-message billing.

- **No long-running Claude process.** Each user turn spawns a fresh `claude -p` subprocess via `ProcessBuilder` (no shell, args as array — no command injection). The process reads the user message from `argv`, streams events to stdout, and exits when the response is complete.
- **Session continuity** is achieved via Claude's `--resume <session-id>` flag, not by keeping a process alive. The first turn for a user gets a `session_id` from Claude's `system/init` event; that ID is stored in `ConcurrentHashMap<userId, sessionId>` inside `ClaudeSessionService` and passed back via `--resume` on every subsequent turn. Full multi-turn memory across page refreshes; each HTTP request is an isolated short-lived process.
- **Binary resolution at boot.** `@PostConstruct` walks an OS-specific candidate list and picks the first path that responds to `--version`:
  - **macOS / Linux**: `/opt/homebrew/bin/claude` → `/usr/local/bin/claude` → `claude` (PATH).
  - **Windows**: `%APPDATA%\npm\claude.cmd` → `%LOCALAPPDATA%\Programs\claude\claude.exe` → `claude.cmd` → `claude.exe` → `claude` (PATH).

  If none respond, a boot warning is logged and the chat endpoint returns errors instead of crashing.

**CLI command shape:**

```bash
claude -p \
  --model <app.chat.cli.model> \
  --output-format stream-json --verbose \
  --include-partial-messages \
  --append-system-prompt "<practice-coach system prompt>" \
  [--resume <sessionId>] \
  "<user message>"
```

- `--model`: model alias (`sonnet`, `opus`, `haiku`) or full ID, sourced from the `app.chat.cli.model` property (default `sonnet`).
- `stream-json` + `--verbose`: emit NDJSON events on stdout (one event per line).
- `--include-partial-messages`: emits **token-level** `stream_event → content_block_delta → text_delta` events as Claude generates them. Without this flag, the full assistant text arrives only in one terminal `assistant` event — perceived as a long pause.
- `--append-system-prompt`: appends the coaching prompt to Claude Code's default system. The prompt biases Claude toward Socratic hints and pattern recognition rather than dumping full solutions unless asked.

#### Provider: `api` — `AnthropicApiChatService`

Calls the Anthropic Messages API directly using `ANTHROPIC_API_KEY`. Uses Spring WebClient with SSE (`bodyToFlux(ServerSentEvent<String>)`) so token deltas stream out the same way as in CLI mode.

- **Conversation memory** is held in-process: `ConcurrentHashMap<userId, List<{role, content}>>`. The full history is replayed on each `/v1/messages` call. Lost on backend restart.
- **Prompt caching**: the system prompt is sent with `cache_control: { type: "ephemeral" }` to take advantage of Anthropic's prompt cache across turns.
- **Defaults**: `claude-sonnet-4-6`, 4096 max tokens.
- **`DELETE /api/chat/session`** in this mode clears the in-memory message history (in CLI mode it clears the pinned `session_id`).

#### Backend → frontend protocol (SSE)

Both providers emit the same three event types so the frontend doesn't care which is wired in. The controller returns a `SseEmitter` from `POST /api/chat/stream`:

| SSE event | Triggered by | Payload | Frontend handling |
|---|---|---|---|
| `status`  | CLI: `system/init` + `system/status:requesting`. API: emitted once on subscribe. | `{ "status": "thinking" \| "requesting" }` | Shows the "Thinking…" indicator with bouncing dots in the assistant message bubble. |
| `delta`   | Each `content_block_delta → text_delta` from the upstream stream. | `{ "text": "<token chunk>" }` | Appended to the streaming assistant message; once the first delta arrives, the thinking indicator is replaced with the actual text + a blinking caret. |
| `error`   | Claude's `result` event with `is_error: true`, an `error` event from the Anthropic API, or any thrown exception. | `{ "text": "<error message>" }` | Surfaced as a red banner under the messages. |

The terminal `assistant` event (CLI mode) and `message_stop` event (API mode) are intentionally **ignored** — token deltas already carried the content.

#### Endpoints (chat)

- `POST /api/chat/stream` — sends a user message, streams the reply as SSE. Requires `X-User-Id`. Gated by `app.chat.enabled=true`.
- `DELETE /api/chat/session` — clears per-user session state (CLI: pinned session ID; API: message history). Requires `X-User-Id`.
- `GET /api/features` — always available, returns `{ chatEnabled, chatProvider }`. Used by the frontend to decide whether to render the Chat tab.

#### Frontend specifics (chat)

- `pages/Chat.jsx` uses raw `fetch` + a manual SSE parser (axios doesn't expose a streaming response body in the browser; `EventSource` only supports GET). The parser splits on blank lines, looks for `event:` and `data:` lines, and JSON-parses the payload.
- Assistant messages are rendered through **`react-markdown`**, so Claude's `**bold**`, `*italic*`, `` `inline code` ``, fenced code blocks, lists, headings, etc. all render with proper formatting. User messages render as plain pre-wrapped text (no markdown, to preserve any literal `*` / `_` the user typed).
- The page-level `AbortController` lets the user press **Stop** to abort the `fetch` stream mid-response. The backend process detects the closed emitter and exits on the next write attempt.
- A **New conversation** button calls `DELETE /api/chat/session` and clears local message state.

#### Limits and gotchas

- **Single-user trust model.** Anyone with access to the backend can hit the chat endpoint and burn the user's Claude Code quota (CLI mode) or Anthropic API spend (API mode). There is no rate limiting, isolation, or auth. Do not expose this app to other users without adding those.
- **CLI mode is tied to the `claude` CLI version.** If Claude Code changes the NDJSON schema (event types, field names, partial-message format), `ClaudeSessionService.handleEvent` may need updating. Currently verified on `claude` 2.1.x.
- **5-minute hard timeout** per turn (`PROCESS_TIMEOUT_SECONDS = 300`). After that, the process is `destroyForcibly()`'d (CLI) or the WebClient subscription is left to time out, and the emitter completes with an error.
- **No DB persistence of chat history.** CLI mode's prior turns live in Claude Code's on-disk session storage (`~/.claude/projects/...`). API mode's history lives in memory only — lost on backend restart. The frontend's transcript is in React state — lost on page refresh.

---

## API Reference

All endpoints return JSON. **User-scoped** endpoints require the `X-User-Id` header (LeetCode username) — the frontend's axios interceptor injects this automatically from `localStorage`. Endpoints with no scope either take the user via path param or operate on global data.

### Problems — `ProblemController`

#### `GET /api/problems` — User-scoped

List all deduplicated problems for the user.

**Query params:** `difficulty?`, `topic?`, `pattern?` (mutually exclusive, evaluated in that order). The no-filter path applies read-time dedup; filtered variants hit the repo directly.

**Response — `List<Problem>`**
```json
[
  {
    "id": 42,
    "leetcodeNumber": 1,
    "title": "Two Sum",
    "difficulty": "EASY",
    "topic": "Array",
    "pattern": "Hashing",
    "solvedDate": "2026-05-10",
    "firstAttempted": "2025-11-02",
    "notes": "hashmap of complements",
    "leetcodeUrl": "https://leetcode.com/problems/two-sum",
    "confidence": 4,
    "lastSubmissionId": 1234567890,
    "lastLang": "java",
    "lastRuntime": "1 ms",
    "lastMemory": "44.8 MB",
    "userId": "example_user",
    "dueDate": null
  }
]
```

Class: `Problem` (`model/Problem.java`).

#### `PUT /api/problems/{id}`

Replace a problem by surrogate ID. `id` and `userId` are reset to the existing row's values server-side; all other fields are overwritten as-sent (partial updates require sending the full record). 404 currently surfaces as 500 from a `RuntimeException`.

**Response:** updated `Problem`.

#### `DELETE /api/problems/{id}`

Hard-delete by surrogate ID. **No user-scope check** — any caller with the ID can delete the row. Returns `204 No Content`.

#### `GET /api/problems/review` — User-scoped

Paginated review queue: problems with `confidence < 3`.

**Query params:** `page` (default `0`), `size` (default `8`), `q?` (exact `leetcode_number` filter).

**Response — `Map<String, Object>`** (ad-hoc)
```json
{
  "content": [ /* Problem[] */ ],
  "totalElements": 17,
  "totalPages": 3,
  "page": 0
}
```

#### `GET /api/problems/{id}/similar`

Other problems sharing the source's `pattern`, scoped to the source's `userId`. Deduplicated by `leetcode_number` (or `title` if null). Returns `[]` if the source has no pattern.

**Response — `List<Problem>`**.

### Stats and accounts — `StatsController`

#### `GET /api/stats` — User-scoped

Aggregate counts from the deduplicated `Problem` table.

**Response — `StatsDTO`**
```json
{
  "totalSolved": 142,
  "easySolved": 60,
  "mediumSolved": 70,
  "hardSolved": 12,
  "byTopic":   { "Array": 35, "String": 18, "Tree": 22 },
  "byPattern": { "Hashing": 14, "Sliding Window": 9, "Two Pointers": 11 },
  "needsReview": 8
}
```

#### `GET /api/stats/heatmap-data` — User-scoped

Year-bucketed heatmap data, computed entirely from the local `Submission` table — no LeetCode call.

**Query params:** `year?` (defaults to current year if missing or ≤ 0).

A day is "new" for a problem if `submission_date = MIN(submission_date)` for that `(user_id, leetcode_number)` across all-time, not just the queried year.

**Response — `Map<String, Object>`**
```json
{
  "lcActivity":       { "2026-05-10": 3, "2026-05-11": 1 },
  "problemActivity":  { "2026-05-10": { "new": 2, "repeated": 1 } }
}
```

#### `GET /api/leaderboard`

All accounts ordered by `ranking` ascending.

**Response — `List<Account>`**
```json
[
  {
    "userId": "example_user",
    "totalSolved": 142,
    "easySolved": 60,
    "mediumSolved": 70,
    "hardSolved": 12,
    "ranking": 184231,
    "acceptanceRate": 62.4,
    "lastSyncedAt": "2026-06-14T08:22:11"
  }
]
```

#### `POST /api/leaderboard/refresh`

Re-fetches every account's profile from LeetCode in parallel (Reactor `flatMap`, unbounded concurrency), upserts each `Account` row, then returns the refreshed ranking-sorted list. Per-account failures are silently swallowed via `onErrorResume(e -> Mono.empty())`.

**Response:** `List<Account>`.

#### `GET /api/account/{userId}`

Lookup a single account by its primary key (LeetCode username). Returns 404 if not present.

**Response — `Account`**.

#### `GET /api/leetcode/{username}`

Proxies LeetCode's profile GraphQL via `LeetCodeService.getProfile`; upserts the result into `Account` as a side effect.

**Response — `LeetCodeProfileDTO`**
```json
{
  "username": "example_user",
  "totalSolved": 142,
  "easySolved": 60,
  "mediumSolved": 70,
  "hardSolved": 12,
  "ranking": 184231,
  "acceptanceRate": 62.4,
  "byTopic": { "Array": 35, "Hash Table": 14, "Tree": 22 }
}
```

`byTopic` here merges all skill tiers (advanced / intermediate / fundamental) from LeetCode's `tagProblemCounts`.

#### `POST /api/leetcode/{username}/sync`

Sync solved problems from LeetCode. The path parameter is also used as the `user_id` for inserted/updated rows. Two modes selected by header presence (see table below). After both modes, the profile is refreshed and `Account` upserted; refresh errors are swallowed but counts still return.

**Query params:** `limit=500` (anonymous mode only).
**Headers (optional):** `X-LC-Session`.

**Response — `Map<String, Object>`**
```json
{ "imported": 12, "updated": 47 }
```

**Sync mode selection:**

| Mode | Trigger | GraphQL query | Writes to `Problem` | Writes to `Submission` | Orphan delete |
|---|---|---|---|---|---|
| Anonymous     | `X-LC-Session` absent  | `recentAcSubmissionList` (capped by `limit`) | Yes (one row per unique slug, enriched via `question`) | No  | No |
| Authenticated | `X-LC-Session` present | `submissionList` (paginated, CSRF first)     | Yes (latest per number wins for metadata) | Yes (PK-deduped against `findSubmissionIdsByUserId`) | Yes — `leetcode_number IS NOT NULL AND last_submission_id IS NULL` |

### Chat — `ChatController`

See the **Chat (Practice Coach)** section above for end-to-end design.

#### `POST /api/chat/stream` — User-scoped

Send a single chat message; the response is streamed back as **Server-Sent Events** (`Content-Type: text/event-stream`). The reply text appears incrementally as Claude generates it. Gated by `app.chat.enabled=true`.

**Header:** `X-User-Id` (required).

**Request body — `ChatRequest`**
```json
{ "message": "Walk me through the Sliding Window pattern with one short example." }
```

**Response:** `Content-Type: text/event-stream`. Three event types: `status`, `delta`, `error` (see table in the Chat section).

The stream ends when the underlying provider's stream completes — the emitter is completed and the HTTP connection closes. No explicit `[DONE]` sentinel is sent.

#### `DELETE /api/chat/session` — User-scoped

Clears per-user session state. In CLI mode, drops the pinned `session_id` so the next message starts fresh in Claude Code. In API mode, drops the in-memory message history.

**Response:** `204 No Content`.

### Features — `FeaturesController`

#### `GET /api/features`

Always available (never gated). Returns the live feature-flag state for the UI.

**Response**
```json
{ "chatEnabled": true, "chatProvider": "cli" }
```

---

## Database tables

### `PROBLEM`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT (PK, auto) | Internal surrogate key |
| `user_id` | VARCHAR (not null) | LeetCode username; scopes all queries |
| `leetcode_number` | INTEGER | Frontend ID (e.g. 1 for Two Sum) |
| `title` | VARCHAR (not null) | Problem title |
| `difficulty` | VARCHAR | Enum string: `EASY`, `MEDIUM`, `HARD` |
| `topic` | VARCHAR | e.g. `Arrays`, `Trees` — sourced from LeetCode `topicTags[0]` |
| `pattern` | VARCHAR | e.g. `Sliding Window` — sourced exclusively from `tuf-mapping.json` |
| `solved_date` | DATE | Date of the most recent accepted submission |
| `due_date` | DATE | Spaced-repetition review date |
| `confidence` | INTEGER | Self-rated 1–5; problems with confidence < 3 appear in the review queue |
| `notes` | VARCHAR(5000) | Free-form notes |
| `leetcode_url` | VARCHAR | Full URL to the problem |
| `last_submission_id` | BIGINT | LeetCode ID of the most recent accepted submission |
| `last_lang` | VARCHAR | Language of last submission |
| `last_runtime` | VARCHAR | Runtime string from LeetCode (e.g. `12 ms`) |
| `last_memory` | VARCHAR | Memory string from LeetCode (e.g. `41.2 MB`) |
| `first_attempted` | DATE | Set once on first sync insert; never updated |

### `ACCOUNT`

| Column | Type | Notes |
|---|---|---|
| `user_id` | VARCHAR (PK) | LeetCode username |
| `total_solved` | INTEGER | |
| `easy_solved` | INTEGER | |
| `medium_solved` | INTEGER | |
| `hard_solved` | INTEGER | |
| `ranking` | INTEGER | Global LeetCode ranking |
| `acceptance_rate` | DOUBLE | |
| `last_synced_at` | TIMESTAMP | When profile was last fetched from LeetCode |

### `SUBMISSION`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT (PK) | LeetCode submission ID — **not** auto-generated; used for deduplication |
| `user_id` | VARCHAR | LeetCode username |
| `leetcode_number` | INTEGER | Problem number |
| `submission_date` | DATE | Local date derived from submission timestamp |
| `submission_timestamp` | BIGINT | Raw Unix epoch seconds from LeetCode |

Indexes: `(user_id, submission_date)` for heatmap date range queries; `(user_id, leetcode_number)` for per-problem lookups.

Only populated by authenticated (token) sync. Anonymous sync does not write to this table.

---

## Frontend (`frontend/src/`)

- **Routing**: React Router v6. Six pages: `Dashboard`, `Problems`, `Review`, `Heatmap`, `Leaderboard`, `Chat`. The Chat tab is hidden when `GET /api/features` reports `chatEnabled: false`.
- **API layer**: `services/api.js` — all HTTP calls go through a single axios instance. A request interceptor injects `X-User-Id` from `localStorage`. Vite proxies `/api` → `http://localhost:8080` in dev. The `chat.stream` helper is the exception: it uses raw `fetch` + a manual SSE parser (axios doesn't expose a streaming response body in the browser) and manually adds the `X-User-Id` header.
- **No state management library** — each page fetches its own data directly.
- **`tuf-mapping.json`** is duplicated in `frontend/src/` and used by `tufLookup.js` to enrich `Problem` rows with topic/pattern when the backend value is null.
- **Charts**: Recharts for bar/pie charts. `react-datepicker` for date inputs.
- **Markdown**: `react-markdown` is used only on the Chat page to render assistant messages.
- **Heatmap page**: Both heatmap grids (LC Submissions and Problem Activity) are built from the same `Submission` table data. Year navigation caches all years in a `useRef` — past years are never re-fetched. The ↻ button always navigates to the current year, clears its cache entry, and increments a `refreshKey` state to force the `useEffect` to re-run even when the year didn't change. The SVG grid uses local date strings (not `toISOString()`) to avoid UTC timezone offset bugs.
- **Chat page**: textarea + scrolling message list; streams SSE deltas into the most recent assistant placeholder; shows a thinking-dots indicator until the first token arrives, then a blinking caret while streaming. **Stop** aborts via `AbortController`; **New conversation** calls `DELETE /api/chat/session` and clears local state.

---

## Security note

This is a single-user, local-only app. There is no auth, no rate limiting, and no tenant isolation. The chat endpoints in particular spend either your Claude Code subscription quota (CLI mode) or your Anthropic API balance (API mode) on every call — do not expose the backend port to the public internet.
