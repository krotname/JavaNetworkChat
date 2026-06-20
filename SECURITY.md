# Security Policy

## Supported versions

Security fixes are handled on the default branch and the latest public release line.

## Reporting vulnerabilities

Do not open a public issue for suspected vulnerabilities, exploit details, protocol abuse cases, or credential leaks.

Report vulnerabilities through GitHub private vulnerability reporting:
https://github.com/krotname/JavaNetworkChat/security/advisories/new

Include:

- affected version or commit,
- reproduction steps,
- network/protocol payloads with secrets redacted,
- impact assessment,
- suggested mitigation if available.

The maintainer aims to acknowledge valid reports within 48 hours and provide a remediation timeline after the impact is confirmed.

## Scope

This policy applies to application code, protocol handling, server lifecycle code, and CI configuration.

## Threat model

Network Chat is a small self-hosted Java chat, not an end-to-end encrypted messenger.

Protected assets:

- chat message contents in transit when TLS is enabled,
- account tokens stored only as salted SHA-256 hashes in the optional accounts file,
- server availability under configured client limits,
- release artifacts, CycloneDX SBOM, checksums, provenance metadata, and GitHub attestations.

Trust boundaries:

- Plain TCP mode is intended only for local development or trusted networks.
- TLS mode protects the socket transport between client and server, but the server can still read
  message contents.
- The accounts file is trusted server configuration; filesystem access to it is administrative
  access.
- GUI preferences store host, port, and username only; account tokens are not persisted by the GUI.

Known limitations:

- No end-to-end encryption, federation, device verification, or forward secrecy beyond the selected
  JSSE TLS configuration.
- No account lockout, password reset, audit log retention, or persistent profile management.
- Private messages are server-mediated and are persisted when history is enabled.
- `NETWORK_CHAT_TLS_TRUST_ALL=true` is a development escape hatch and must not be used for production
  deployments.

Operational recommendations:

- Enable TLS outside localhost/trusted lab networks.
- Keep `accounts.csv` readable only by the server operator.
- Rotate tokens by replacing account-file rows and restarting the server.
- Publish release zip files together with `checksums.txt`, CycloneDX SBOM, `provenance.json`, and GitHub attestations.
