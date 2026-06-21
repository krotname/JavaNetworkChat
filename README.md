# Network Chat

[![CI](https://github.com/krotname/JavaNetworkChat/actions/workflows/ci.yml/badge.svg)](https://github.com/krotname/JavaNetworkChat/actions/workflows/ci.yml)
[![CodeQL](https://github.com/krotname/JavaNetworkChat/actions/workflows/codeql.yml/badge.svg)](https://github.com/krotname/JavaNetworkChat/actions/workflows/codeql.yml)
[![Coverage](https://codecov.io/gh/krotname/JavaNetworkChat/branch/main/graph/badge.svg)](https://app.codecov.io/gh/krotname/JavaNetworkChat)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/krotname/JavaNetworkChat/badge)](https://securityscorecards.dev/viewer/?uri=github.com/krotname/JavaNetworkChat)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/13154/badge)](https://www.bestpractices.dev/projects/13154)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-007396)](https://adoptium.net/)

![Java Network Chat](docs/assets/project-icon.svg)

Документация на английском языке: [README.en.md](README.en.md).

## Возможности

Network Chat — это Java 21 приложение для сетевого чата поверх TCP сокетов:

- сервер с handshake и рассылкой сообщений;
- консольный клиент;
- бот-клиент с командами времени/даты;
- GUI клиент на Swing со встроенной панелью подключения, retry/cancel, сохранением последних
  настроек, локальной лентой сообщений, комнатами, приватными сообщениями и разделением на MVC;
- опциональная файловая история сообщений с replay последних сообщений комнаты после рестарта;
- опциональный TLS-режим, token-based accounts с ролями `USER`/`ADMIN` и admin-команда
  `/health`;
- Windows release zip с launch scripts, checksums, CycloneDX SBOM и provenance metadata;
- воспроизводимая Gradle-сборка, тесты, CI и проверки качества.

![Swing GUI client](docs/images/gui-client.svg)

## Запуск

```bash
./gradlew runServer --args="--port 1500"
./gradlew runClient
./gradlew runBotClient
./gradlew runGuiClient
```

Сервер по умолчанию слушает порт `1500`. Для программного запуска используйте
`ChatServerConfig`: он задаёт порт, максимальное число клиентов, timeout handshake и timeout чтения
после handshake.

Чтобы включить файловую историю:

```bash
./gradlew runServer --args="--port 1500 --history build/chat-history.jsonl"
```

Чтобы включить accounts, сначала создайте строку для `accounts.csv`:

```bash
./gradlew createAccount --args="alice USER secret" >> build/accounts.csv
./gradlew createAccount --args="admin ADMIN admin-secret" >> build/accounts.csv
./gradlew runServer --args="--port 1500 --accounts build/accounts.csv"
```

Клиенты передают token через GUI-поле `Токен` или переменную окружения `NETWORK_CHAT_TOKEN`.
Admin-пользователь может отправить `/health` и получить приватный ответ со статусом сервера.

Чтобы включить TLS, серверу нужен Java keystore:

```bash
keytool -genkeypair -alias network-chat -keyalg RSA -keysize 3072 -validity 365 \
  -keystore build/network-chat.p12 -storetype PKCS12 -storepass changeit
./gradlew runServer --args="--port 1500 --tls-keystore build/network-chat.p12 --tls-password changeit"
```

Клиент включает TLS через окружение:

```powershell
$env:NETWORK_CHAT_TLS="true"
$env:NETWORK_CHAT_TRUSTSTORE="build/network-chat.p12"
$env:NETWORK_CHAT_TRUSTSTORE_PASSWORD="changeit"
./gradlew runGuiClient
```

Релизный набор без Gradle на машине пользователя собирается командой:

```bash
./gradlew releaseBundle
```

Артефакты появятся в `build/release`: Windows zip, `checksums.txt`, CycloneDX SBOM и `provenance.json`.

Проверка релиза:

```bash
sha256sum -c checksums.txt
gh attestation verify network-chat-*-windows.zip --repo krotname/JavaNetworkChat
```

## Архитектура и протокол

Краткий архитектурный контракт описан в [docs/architecture.md](docs/architecture.md).

- `ChatServer` принимает TCP-соединения и обрабатывает клиентов в bounded executor.
- `ChatConnection` читает и пишет однострочные UTF-8 JSON frames.
- `ChatProtocol` сериализует `ChatMessage`.
- `ChatMessage` содержит `protocolVersion`; unversioned клиенты получают явный `ERROR`.
- Для `TEXT` сообщений `data` содержит только исходный текст, а `sender` содержит автора.
- `TEXT` сообщения рассылаются всем клиентам, включая отправителя, чтобы пользователь видел своё
  сообщение в ленте.
- `ROOM_TEXT` доставляется только участникам комнаты; `PRIVATE_TEXT` доставляется отправителю и
  адресату.
- GUI хранит локальную ленту текущей сессии, оформляет `USER_ADDED`/`USER_REMOVED` как service
  events, использует `messageId` для дедупликации, поддерживает поиск по тексту, автору,
  дате/timestamp, комнате, адресату и экспорт JSON/CSV.
- При включённой истории сервер сохраняет `ROOM_TEXT`/`PRIVATE_TEXT` в JSONL, ограничивает размер
  истории, мигрирует старые unversioned `TEXT` записи и отправляет последние сообщения комнаты при
  входе.
- При включённых accounts сервер принимает только `USER_NAME` с корректным token; роли используются
  для admin-команд.
- TLS включается конфигурацией сервера и переменными окружения клиента.
- Console и Swing клиенты сами форматируют отображение вида `alice: hello`.
- Bot client отвечает на команды времени/даты по `data`, используя автора из `sender`.

## Тесты и качество

- `./gradlew test`
- `./gradlew integrationTest`
- `./gradlew uiTest`
- `./gradlew check`
- `./gradlew jacocoAllReport`
- `./gradlew jacocoTestCoverageVerification`

В CI HTML-отчёт JaCoCo публикуется как artifact, а line/branch coverage добавляется в GitHub
Actions Summary для Linux job.

## Стратегия тестирования

- **Unit-тесты** (`src/test/java`) — протокол, bot-команды, модель GUI.
- **Интеграционные тесты** (`src/integrationTest/java`) — подключение клиентов, handshake, лимиты сервера, timeout и обмен сообщениями.
- **UI smoke тесты** (`src/uiTest/java`) — проверка отрисовки состояния окна чата.
- **План развития** — больше негативных сценариев протокола и проверок отказоустойчивости медленных клиентов.

Для оценки покрытия используется JaCoCo: в CI порог для ядра (`network` + `protocol`) — `70%/65%` (`line`/`branch`).

## Troubleshooting

- `Address already in use`: запустите сервер на другом порту, например `./gradlew runServer --args="--port 1600"`.
- GUI не показывает окно в CI: UI smoke тесты автоматически пропускаются в headless окружении.
- Клиент сразу отключился: проверьте уникальность имени и длину ника (`3..64`, буквы, цифры, `_`, `-`).
- Клиент получил `Server is busy`: достигнут `maxClients` из `ChatServerConfig`.
- GUI показывает `Нет соединения`: проверьте адрес/порт и используйте кнопку `Повторить`; последние
  введённые настройки сохраняются локально.
- Повреждённая строка в history-файле пропускается при старте; валидная история продолжает
  загружаться.
- `Authentication failed`: проверьте строку пользователя в `accounts.csv` и token в GUI или
  `NETWORK_CHAT_TOKEN`.
- Ошибка TLS trust: укажите truststore клиента через `NETWORK_CHAT_TRUSTSTORE` или используйте
  сертификат, которому доверяет JVM.

## Структура репозитория

- `src/main/java` — код приложения.
- `src/test/java` — unit-тесты.
- `src/integrationTest/java` — интеграционные тесты.
- `src/uiTest/java` — smoke тесты UI.
- `docs` — архитектурные заметки и визуальные материалы.
- `.github/workflows` — CI и проверки безопасности.

## Дополнительные сигналы качества

- CI на Linux и Windows.
- Авто-проверки: Checkstyle, Spotless, SpotBugs, JaCoCo.
- Security проверки: CodeQL и OpenSSF Scorecard.
- Dependabot с группировкой обновлений зависимостей и Actions.
- Релизные артефакты: zip, `checksums.txt`, CycloneDX SBOM, provenance metadata и GitHub attestations.
- Явно оформленные файлы `CONTRIBUTING.md` и `SECURITY.md`.

## Roadmap

- v1.6.x: TLS, token accounts, release packaging и security hardening реализованы в текущей линии.
- Позже: персистентные профили пользователей и расширенное администрирование отдельными
  функциональными этапами.
