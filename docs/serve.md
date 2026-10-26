# Server mode

`--profile server` serves the [RPC protocol](rpc.md) over a socket instead of
stdin/stdout, and **a connection is an agent**: each client gets its own agent,
its own session, and its own working directory.

```sh
kmet --profile server                      # TCP on 127.0.0.1, an OS-chosen port
kmet --profile server --port 7777          # TCP on 127.0.0.1:7777
kmet --profile server --socket /tmp/kmet.sock
```

The server announces what it bound — and the token — on its own stdout before
it accepts anything:

```clojure
{:type :listening :transport :tcp :port 44731 :token "…" :max-sessions 8 :idle-timeout 1800}
{:type :listening :transport :unix :socket "/tmp/kmet.sock" :token "…" :max-sessions 8 :idle-timeout 1800}
```

`--port 0` (the default) asks the OS for a free port. TCP is bound to loopback
only: phase 1 is one host and one principal — many clients, one identity — and
there is no flag that exposes the socket on an interface. `--socket` needs a
host with unix-domain sockets (the babashka and JVM builds have them); where
there is none (a Jolt build) the server says so on stderr and falls back to TCP
loopback, so one invocation stays portable.

## Connecting

A fresh connection is greeted before it is asked anything:

```clojure
{:type :hello :auth-required true :transport :tcp :port 44731}
```

The first record a client sends authenticates it:

```clojure
{:type :auth :token "…"}
```

The answer is the connection's agent:

```clojure
{:type :ready :agent-id "7d9070f3" :session-id "1a178f32cc6-34fbe4c1"}
```

Every record after `:ready` — responses and events alike — carries that
`:agent-id`, so a client that multiplexes connections into one log can always
tell them apart. A command sent before the `:auth` is refused by name
(`authenticate first`), a wrong token is refused (`authentication failed`), and
either refusal closes the connection: no agent exists for a connection that has
not authenticated.

The token is generated when `--token` is absent and announced in the
`:listening` record — the supervisor's channel, the same one the port uses. A
token passed with `--token` is not echoed: it is already the caller's secret,
and stdout is a log.

## The world a connection brings

An `:auth` record may also name the world its agent runs in:

| Field | Meaning |
|---|---|
| `:cwd` | the directory the agent's session lives in: relative paths, the shell and the tool layer all resolve there |
| `:config` | settings overrides, merged over the process's loaded settings |
| `:model`, `:provider`, `:thinking`, `:models`, `:system-prompt`, `:append-system-prompt`, `:session-dir` | the per-run overrides `--model` and friends set for one run |

```clojure
{:type :auth :token "…"
 :cwd "/home/me/project-a"
 :config {:shell-command-prefix "conda activate a &&"}}
```

A relative `:cwd` resolves against the server's working directory.

Two connections of one server are two agents in two worlds, and no process-wide
cell moved to make that true. Credentials are the exception: `:auth` is the
process's, by the phase-1 single-principal decision. A `:cwd` that is not an
existing directory is refused rather than turned into an agent whose every tool
call resolves against a path nobody meant.

## Limits

| Flag | Default | Meaning |
|---|---|---|
| `--max-sessions <n>` | 8 | connections served at once; `0` is no cap. A connection past the cap is answered with an error and closed. |
| `--queue-depth <n>` | 256 | records buffered per connection. |
| `--idle-timeout <s>` | 1800 | a connection silent this long, with no run in flight, is released and its agent disposed; `0` never reaps. |

Durations are measured on the monotonic clock
(`kmet.libs.concurrent/monotonic-ms`), and a run in flight is never reaped:
only a quiet, idle connection is.

## A slow client, and the gap

A run's records are handed to the connection's bounded queue and written by one
writer thread, so a client that stops reading costs its own buffer and never
the turn. When the queue is full the **oldest** record is dropped and counted;
the next record is preceded by a gap:

```clojure
{:type :stream-gap :dropped 1180 :agent-id "7d9070f3"}
```

A gap means "the stream has a hole, and this many records went into it". The
client resyncs with `get-state` (and `get-entries` for the transcript). Nothing
is lost silently in the middle of a message: the newest record — the end of one
— is what survives.

## Approvals

`--profile server` replaces the bundle's deny-by-default approver with one that
asks the client. When an agent's policy answers `:ask`, the question travels its
own connection:

```clojure
;; the server asks
{:type :request :id "req-…" :method :ask :tool-name "bash" :args {...} :agent-id "7d9070f3"}

;; the client answers: {:decision :allow} or {:decision :deny :reason "…"}
{:type :response :id "req-…" :data {:decision :allow}}
```

The run blocks until the answer arrives — that is what a question means — so a
client that cannot ask should keep the default approver. A connection that
closes answers its outstanding questions with a deny.

The *policy* is not mounted by the profile. Stack `:bundle/gated` (or patch
`{:id :permissions :plugin kmet.app.permissions/permissions :config {:policy :ask-all}}`)
to have the gate decide per agent; the server's row is only the delivery seam.

## Lifecycle

A connection's socket closing disposes its agent. The server serves until its
stdin closes — how a parent process stops one — then disposes the connections it
still holds and exits 0. The session log is EDN on disk, so a client that wants
a session to outlive its socket keeps the session id and resumes it
(`switch-session`, or the registry's resume-by-id); the server adds no second
store.

`--profile server --dump-state` mounts the whole composition with the mode row
inert: a diagnostic boot opens no socket and proves the rows compose.
