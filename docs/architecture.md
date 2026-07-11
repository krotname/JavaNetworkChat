# Network Chat Architecture

Network Chat is a small Java 21 TCP chat application with a deliberately simple runtime shape:

```text
clients <-> ChatConnection <-> ChatProtocol <-> ChatServer
```

## Runtime Flow

1. `ChatServer` opens a `ServerSocket` on the configured port.
2. Each accepted socket is handled by a bounded client executor.
3. The server sends `NAME_REQUEST`, waits for `USER_NAME`, validates token credentials when accounts
   are configured, validates uniqueness, then responds with `NAME_ACCEPTED`.
4. All registered users, including the current client, are sent to the new client as `USER_ADDED`
   events.
5. The client joins `general` by default; additional rooms are created when a client sends
   `ROOM_JOIN`.
6. `ROOM_TEXT` messages are broadcast only to members of the target room, including the sender.
7. `PRIVATE_TEXT` messages are delivered only to the sender and recipient.
8. If history is configured, persistable text frames are stored in a bounded JSONL file and recent
   room history is replayed after `ROOM_JOINED`.
9. Admin users can send `/health` to receive a private server status frame.
10. Closing or failed connections are removed and announced with `USER_REMOVED`.

## Message Frames

Frames are one-line UTF-8 JSON objects serialized by `ChatProtocol`.

- `type` is required for every frame.
- `protocolVersion` must match the current `ChatMessage.PROTOCOL_VERSION`; unversioned frames are
  rejected with an explicit `ERROR`.
- `data` is optional for control frames, but required and non-blank for `TEXT`, `ROOM_TEXT`, and
  `PRIVATE_TEXT`.
- `sender` carries the author for text frames; clients own display formatting.
- `room` carries room routing for `ROOM_TEXT`, `ROOM_JOIN`, `ROOM_LEAVE`, `ROOM_ADDED`,
  `ROOM_JOINED`, and `ROOM_LEFT`.
- `recipient` carries the target user for `PRIVATE_TEXT`.
- Clients may supply `timestamp` and `messageId`, but the server replaces both when it normalizes a
  room or private message so clients cannot forge identity or ordering metadata.
- `data` is limited by `ChatMessage.MAX_DATA_LENGTH`.
- Encoded frames are limited to 8 KiB and reads use a total deadline to defeat slow trickle input.
- Account tokens are carried in the existing `USER_NAME` frame as `username|base64(token)` so older
  plain username clients remain compatible when accounts are disabled.

## Server Limits

`ChatServerConfig` centralizes runtime limits:

- `port` - TCP port, default `1500`.
- `bindAddress` - listen address, default loopback-only `127.0.0.1`.
- `maxClients` - maximum concurrent client handler threads, default `100`.
- `handshakeTimeout` - maximum time to complete username registration, default `10s`.
- `readTimeout` - idle socket read timeout after handshake, default `5m`.
- `historyFile` / `historyLimit` / `historyReplayLimit` - optional replayable JSONL history.
- `accountFile` - optional account registry.
- `tls` - optional JSSE TLS server socket configuration.

The legacy `new ChatServer(int port)` constructor delegates to `ChatServerConfig.ofPort(port)`.

## Security And Operations

TLS is transport-level and optional. The server loads a Java keystore from `--tls-keystore`; its
passwords come only from `NETWORK_CHAT_TLS_KEYSTORE_PASSWORD` and
`NETWORK_CHAT_TLS_KEY_PASSWORD`. Clients opt in through `NETWORK_CHAT_TLS` plus an optional
truststore and verify the server hostname. Plain TCP remains the loopback-only default for local
development and integration tests.

Accounts are optional and loaded from a CSV file:

```text
username,role,16-byte-hex-salt,pbkdf2-sha256$210000$derived-hash
```

`AccountTool` / `./gradlew --quiet createAccount --args="alice USER"` reads the token from the
console/stdin and prints one ready-to-append row without exposing the token in process arguments.
Canonical unversioned SHA-256 rows produced by older releases remain readable as a migration path;
operators should rotate them to the versioned PBKDF2 format.
When accounts are enabled, clients must provide the token through the GUI token field or
`NETWORK_CHAT_TOKEN`. Roles are deliberately coarse: `USER` can chat, `ADMIN` can additionally run
server-only admin commands such as `/health`.

Server logs use compact JSON-line messages for important lifecycle/auth events while keeping Java
`java.util.logging` as the runtime logging backend.

## Client Model

`ChatClient` owns the socket lifecycle and exposes hooks for console, bot, and Swing clients.
Connection status is updated through a final template method before client-specific UI or console
side effects run, so subclasses cannot skip the shared latch/status update.

The Swing client keeps connection settings in a small local preferences store, renders an embedded
connection panel instead of modal startup prompts, and switches to a read-only disconnected state with
a reconnect action when the socket loop fails after a successful session.

The Swing model stores a bounded local timeline for the current app session. Text frames are
deduplicated by `messageId`, own messages are rendered as `Вы`, and user add/remove protocol events
are appended as service events instead of ordinary chat text.

Room membership lives on the server. `general` always exists, room creation is idempotent through
`ROOM_JOIN`, distinct rooms are capped at 1,000, and clients that try to send to a room before
joining receive an explicit `ERROR`.

## History Store

History is optional and enabled through `ChatServerConfig.withHistory(...)` or server CLI
`--history`. The store is a UTF-8 JSONL file where each line is a versioned `ChatMessage`.

- `ROOM_TEXT` and `PRIVATE_TEXT` are persistable.
- The server keeps a bounded in-memory view and atomically replaces the file after saves.
- File size, individual frame size, and symbolic-link traversal are rejected before processing.
- Invalid/corrupt lines, including malformed UTF-8 records, are skipped at startup so one bad record
  does not prevent the server from starting.
- Legacy unversioned `TEXT` records are migrated to current `ROOM_TEXT/general` records at load time.
- Known rooms can be recovered from stored room messages.
- On room join the server replays the last `historyReplayLimit` room messages to that client.

## Release Artifacts

`./gradlew releaseBundle` builds:

- `network-chat-<version>-windows.zip` with `bin/*.bat` launchers and runtime dependencies.
- `checksums.txt` with SHA-256 digests.
- `provenance.json` with local SLSA-style subject metadata for the zip.
