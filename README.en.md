# Network Chat

[![CI](https://github.com/krotname/JavaNetworkChat/actions/workflows/ci.yml/badge.svg)](https://github.com/krotname/JavaNetworkChat/actions/workflows/ci.yml)
[![CodeQL](https://github.com/krotname/JavaNetworkChat/actions/workflows/codeql.yml/badge.svg)](https://github.com/krotname/JavaNetworkChat/actions/workflows/codeql.yml)
[![Coverage](https://codecov.io/gh/krotname/JavaNetworkChat/branch/main/graph/badge.svg)](https://app.codecov.io/gh/krotname/JavaNetworkChat)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/krotname/JavaNetworkChat/badge)](https://securityscorecards.dev/viewer/?uri=github.com/krotname/JavaNetworkChat)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-007396)](https://adoptium.net/)

## Capabilities

Network Chat is a Java 21 chat application over TCP sockets with:

- a resilient server with user handshake and broadcast.
- a console client.
- a bot client with date/time commands.
- a Swing GUI client with an embedded connection panel, retry/cancel flow, saved last settings,
  local message timeline, rooms, private messages, and MVC style structure.
- optional file-backed message history with room replay after server restart.
- optional TLS mode, token-based accounts with `USER`/`ADMIN` roles, and the admin `/health`
  command.
- a Windows release zip with launch scripts, checksums, CycloneDX SBOM, and provenance metadata.
- reproducible Gradle build, tests, CI, and quality gates.

![Swing GUI client](docs/images/gui-client.svg)

## Run locally

### Prerequisites

- Java 21+

### Commands

```bash
./gradlew runServer --args="--port 1500"
./gradlew runClient
./gradlew runBotClient
./gradlew runGuiClient
```

Server and clients can also be run directly with Java by building jars from Gradle.

The default server port is `1500`, and the server binds only to `127.0.0.1`. Programmatic server
startup can use `ChatServerConfig` to set the bind address, port, maximum client count, handshake
timeout, and post-handshake read timeout.

To enable file-backed history:

```bash
./gradlew runServer --args="--port 1500 --history build/chat-history.jsonl"
```

To enable accounts, first generate rows for `accounts.csv`:

```bash
./gradlew --quiet createAccount --args="alice USER" >> build/accounts.csv
./gradlew --quiet createAccount --args="admin ADMIN" >> build/accounts.csv
./gradlew runServer --args="--port 1500 --accounts build/accounts.csv"
```

The command prompts for the token through the console/stdin, so it is not exposed in the process
arguments. New rows contain a versioned PBKDF2-HMAC-SHA256 hash. Canonical legacy rows that used
fast SHA-256 remain usable for migration, but should be replaced with `createAccount`.

Clients send the token through the GUI `Token` field or the `NETWORK_CHAT_TOKEN` environment
variable. Admin users can send `/health` and receive a private server status response.

To enable TLS, create a Java keystore for the server:

```bash
export NETWORK_CHAT_TLS_KEYSTORE_PASSWORD='changeit'
export NETWORK_CHAT_TLS_KEY_PASSWORD='changeit'
keytool -genkeypair -alias network-chat -keyalg RSA -keysize 3072 -validity 365 \
  -keystore build/network-chat.p12 -storetype PKCS12 \
  -storepass:env NETWORK_CHAT_TLS_KEYSTORE_PASSWORD
./gradlew runServer --args="--port 1500 --tls-keystore build/network-chat.p12"
```

For remote access, explicitly add `--bind 0.0.0.0` (or a specific address) and always enable TLS
and accounts. The certificate must contain a SAN for the host name/address used by clients.

Clients enable TLS through environment variables:

```powershell
$env:NETWORK_CHAT_TLS="true"
$env:NETWORK_CHAT_TRUSTSTORE="build/network-chat.p12"
$env:NETWORK_CHAT_TRUSTSTORE_PASSWORD="changeit"
./gradlew runGuiClient
```

To build a no-Gradle release package for end users:

```bash
./gradlew releaseBundle
```

Artifacts are written to `build/release`: the Windows zip, `checksums.txt`, CycloneDX SBOM, and `provenance.json`.

Release verification:

```bash
sha256sum -c checksums.txt
gh attestation verify network-chat-*-windows.zip --repo krotname/JavaNetworkChat
```

## Architecture and protocol

The compact architecture contract is documented in [docs/architecture.md](docs/architecture.md).

- `ChatServer` accepts TCP connections and handles clients in a bounded executor.
- `ChatConnection` reads and writes UTF-8 JSON frames capped at 8 KiB with a total deadline.
- `ChatProtocol` serializes `ChatMessage`.
- `ChatMessage` carries `protocolVersion`; unversioned clients receive an explicit `ERROR`.
- For `TEXT` messages, `data` contains only raw text and `sender` contains the author.
- `TEXT` messages are broadcast to every client, including the sender, so users see their own sent
  messages in the timeline.
- `ROOM_TEXT` is delivered only to room members; `PRIVATE_TEXT` is delivered only to the sender and
  recipient.
- The GUI keeps a bounded local timeline for the current session, renders `USER_ADDED`/`USER_REMOVED`
  as service events, uses `messageId` for deduplication, and supports search by text, sender,
  date/timestamp, room, recipient plus JSON/CSV export.
- When history is enabled, the server atomically stores `ROOM_TEXT`/`PRIVATE_TEXT` frames in bounded
  JSONL, migrates legacy unversioned `TEXT` records, and replays recent room messages on join.
- When accounts are enabled, the server accepts only `USER_NAME` frames with a valid token; roles are
  used for admin-only commands.
- TLS is enabled through server configuration and client environment variables.
- Console and Swing clients format display text such as `alice: hello`.
- The bot client reads date/time commands from `data` and uses the author from `sender`.

## Project structure

- `src/main/java` — core application classes.
- `src/test/java` — unit tests.
- `src/integrationTest/java` — protocol and network integration tests.
- `src/uiTest/java` — Swing smoke tests.
- `docs` — architecture notes and visual assets.
- `.github/workflows` — CI, CodeQL and Scorecard workflows.

## Testing

- `./gradlew test`
- `./gradlew integrationTest`
- `./gradlew uiTest`
- `./gradlew check`
- `./gradlew jacocoTestCoverageVerification`
- `./gradlew jacocoAllReport` (CI artifact source)

Coverage thresholds are enforced in Gradle and CI. The HTML JaCoCo report is uploaded as a CI
artifact, and line/branch coverage is published to the GitHub Actions Summary for the Linux job.

### Test strategy

- **Unit tests** (`src/test/java`) check protocol, bot command handling, and UI model invariants.
- **Integration tests** (`src/integrationTest/java`) exercise full server/client socket flow, handshake
  failures, resource limits, timeouts, and multiple peers.
- **UI smoke tests** (`src/uiTest/java`) verify Swing state rendering.
- **Future hardening tests**: contract validation and error-handling matrix can be added in the same
  structure.

## Quality gates

The repository runs:

- `checkstyle` for style and API cleanliness,
- `spotless` for deterministic formatting,
- `spotbugs` for bug-pattern analysis,
- `jaCoCo` line/branch coverage gate on core network/protocol layers (`70%/65%`),
- GitHub Actions pipeline on Linux + Windows,
- grouped dependency and workflow update signals via Dependabot,
- CodeQL and OpenSSF Scorecard security scans.

The quality surface keeps the `main` branch auditable and maintainable through automated checks,
explicit licensing, and operating docs.

## Language

- English project documentation in this file.
- Russian project documentation in `README.md`.

## Maintenance model

This repository is organized for maintainability:

- explicit package structure,
- reproducible build and command surface (`./gradlew`),
- consistent protocol encoding,
- targeted comments in non-trivial methods,
- automated quality gates.

## Additional quality signals

- Linux + Windows CI matrix for broader platform evidence.
- Automated quality gates via Checkstyle, Spotless, SpotBugs and JaCoCo.
- Security checks via CodeQL and OpenSSF Scorecard.
- Dependency and workflow automation via Dependabot.
- Release artifacts: zip, `checksums.txt`, CycloneDX SBOM, provenance metadata, and GitHub attestations.
- Explicit contributor and security docs.

## Troubleshooting

- `Address already in use`: run the server on another port, for example `./gradlew runServer --args="--port 1600"`.
- GUI does not render in CI: UI smoke tests skip automatically in headless environments.
- Client disconnects immediately: check username uniqueness and nickname length (`3..64`, letters, digits, `_`, `-`).
- Client receives `Server is busy`: the configured `ChatServerConfig.maxClients` limit has been reached.
- GUI shows `No connection`: check host/port and use the reconnect button; the last entered settings
  are stored locally.
- A corrupt line in the history file is skipped during startup; valid history still loads.
- `Authentication failed`: check the user row in `accounts.csv` and the GUI token field or
  `NETWORK_CHAT_TOKEN`.
- TLS trust errors: set `NETWORK_CHAT_TRUSTSTORE` on the client or use a certificate trusted by the
  JVM.

## Roadmap

- v1.6.x: TLS, token accounts, release packaging, and security hardening are implemented in the
  current line.
- Later: persistent user profiles and richer administration as separate product-focused phases.
