# Changelog

## Unreleased

- Reclaimed empty rooms on last leave and on disconnect, announced them with the new `ROOM_REMOVED`
  frame, and taught the console and Swing clients to drop reclaimed rooms.
- Bounded distinct rooms with a configurable `maxRooms` limit (default 256, CLI `--max-rooms`) and
  answered over-limit `ROOM_JOIN` with an explicit `ERROR`.
- Added per-connection token buckets for inbound frames and room creation that reply `ERROR` instead
  of dropping the connection.

## 1.6.1 - 2026-06-19

- Hardened account storage, history migration, TLS socket handling, and integration coverage after repository audit.
- Refreshed Gradle wrapper, formatter gate, and CI/release supply-chain checks.

## 1.6.0 - 2026-06-12

- Reworked Swing connection flow into a single settings dialog with defaults, cancel, and retry.
- Added GUI status bar, send button, outgoing message validation, and graceful disconnect on close.
- Preserved exact connection failure reasons for GUI retry flows.
- Broadcast text messages back to the sender and include the current user in the initial user list.
- Resolved console client settings in host/port/user order and fixed EOF handling for piped input.
- Changed connection close order so closing a client unblocks pending socket reads.
- Fixed UI smoke coverage to exercise the controller-owned chat window.
- Wired Gradle `runClient` to standard input for interactive console runs.
- Added embedded Swing connection panel, last-settings preferences, participant count in the status
  bar, read-only disconnect state, and reconnect button with a short backoff.
- Replaced the GUI latest-message slot with a bounded local timeline, service events, copy/select
  all/clear controls, own-message rendering, and `messageId`-based deduplication.
- Preserved client `messageId` and timestamp when the server echoes normalized text messages.
- Added protocol versioning metadata plus room/private message frame types.
- Added server-managed rooms with default `general`, room creation on join, room-scoped broadcasts,
  leave events, and private sender/recipient delivery.
- Added Swing room selection, join/leave controls, optional private recipient input, and room/private
  timeline rendering.
- Added integration coverage for room-only delivery, private-only delivery, room join/leave, and
  explicit protocol errors for unversioned clients.
- Added optional file-backed JSONL server history with bounded rotation, corrupt-line tolerance, and
  room replay after restart.
- Added local GUI timeline search by text, sender, date/timestamp, room, and recipient plus JSON/CSV
  timeline export.
- Added tests for history persistence, rotation, corrupt startup data, restart replay, search, and
  export.
- Added legacy JSONL history migration for unversioned `TEXT` records.
- Added optional TLS server/client socket mode through JSSE keystore/truststore configuration.
- Added optional file-backed token accounts, salted SHA-256 token hashes, `USER`/`ADMIN` roles, and a
  `createAccount` helper.
- Added admin `/health` command with private server status responses and structured lifecycle/auth
  logs.
- Added Windows release zip packaging plus SHA-256 checksums and local provenance metadata.
- Updated release workflow to build and upload release artifacts with checksums/provenance.
- Expanded security documentation with chat threat model, trust boundaries, and deployment
  recommendations.
- Fixed a client shutdown race where fast console `exit` could close the socket while the reader
  thread still used the shared connection reference.

## 1.1.0

- Added `ChatServerConfig` with server limits and socket timeout settings.
- Made server client handling bounded and explicit about busy rejections.
- Split protocol text payloads from display formatting: `data` is raw text, `sender` is the author.
- Fixed GUI connection lifecycle by preserving the base client status update path.
- Added architecture documentation, grouped Dependabot updates, version catalog, and CI coverage summary.
- Lowered the JaCoCo line coverage gate to 70% while keeping the branch threshold at 65%.

## 1.0.0

- Reworked project into Gradle Java 21 multi-layer architecture.
- Added protocol-based message transport over TCP.
- Added CLI client, GUI client, and bot client implementations.
- Added integration and UI smoke test sets.
- Added GitHub Actions workflows, coverage gating, and security checks.
