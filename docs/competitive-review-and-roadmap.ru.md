# JavaNetworkChat: обзор конкурентов, UI/UX аудит и план релизов

Дата исследования: 2026-06-12.
База анализа: локальная копия `krotname/JavaNetworkChat`, commit
`a50f1d055d98f15194597882b97139a1a0462cd1`, версия `1.1.0`.

## Ограничения проверки

- Репозиторий уже был скачан в `C:\Users\KRT\Documents\GitHub\JavaNetworkChat`.
- `gh auth status` завис на таймауте, а `git fetch --prune` завершился ошибкой DNS:
  `Could not resolve host: github.com`.
- Поэтому кодовая проверка выполнена по локальному checkout. Внешний анализ аналогов выполнен по
  публичным web-источникам.

## Зачем нужно приложение

`JavaNetworkChat` - учебно-прикладной сетевой чат на Java 21. Он полезен как компактная демонстрация:

- TCP-сервера с handshake, лимитом клиентов и широковещательной рассылкой;
- консольного клиента;
- Swing GUI клиента;
- bot-клиента, который отвечает на команды даты и времени;
- JSON-протокола поверх однострочных UTF-8 frames;
- Gradle-сборки с unit, integration, UI smoke, Checkstyle, SpotBugs, Spotless, JaCoCo и CI.

Это не готовый конкурент Slack/Element/Mattermost и даже не полноценный LAN messenger. Текущая
ценность проекта - демонстрация сетевой архитектуры, качества сборки и тестовой инфраструктуры.

## Как работает

1. `ChatServer` открывает `ServerSocket` на порту `1500` или заданном порту.
2. Для каждого клиента сервер отправляет `NAME_REQUEST`.
3. Клиент отвечает `USER_NAME`.
4. Сервер проверяет имя: длина `3..64`, буквы/цифры/`_`/`-`, уникальность.
5. При успехе сервер отправляет `NAME_ACCEPTED`.
6. Новому клиенту отправляются все зарегистрированные пользователи, включая его самого, через
   `USER_ADDED`.
7. `TEXT` сообщения пересылаются всем клиентам, включая отправителя; `messageId` исходного frame
   сохраняется для дедупликации в GUI.
8. `ROOM_JOIN` создаёт комнату при необходимости, `ROOM_TEXT` доставляется только участникам
   комнаты, а `PRIVATE_TEXT` только отправителю и адресату.
9. При включённом `--history` сервер сохраняет текстовые сообщения в JSONL и отправляет последние
   сообщения комнаты после входа.
10. При отключении сервер отправляет остальным `USER_REMOVED`.
11. `BotChatClient` подключается как `date_bot_*` и отвечает на команды:
   `дата`, `день`, `месяц`, `год`, `время`, `час`, `минуты`, `секунды`.

Ручная проверка запуска:

- `./gradlew.bat check` - успешно.
- Запущен `runServer` на `1500`.
- Запущен `runBotClient`.
- Через TCP-протокол подключен клиент `alice`.
- Команда `время` вернула ответ вида
  `{"type":"TEXT","data":"Информация для alice: 13:11:14","sender":"date_bot_871",...}`.

## Рейтинг аналогов

Методика: оценивались зрелость продукта, удобство первого запуска, наличие GUI, безопасность,
история сообщений, управление пользователями, файловый обмен, мобильные/desktop клиенты, активность
релизов и пригодность как реальный инструмент общения.

| Место | Продукт | Тип | Почему выше JavaNetworkChat |
| --- | --- | --- | --- |
| 1 | Mattermost | self-hosted team chat | зрелый сервер, web/desktop/mobile клиенты, роли, каналы, интеграции, enterprise-функции, регулярные релизы |
| 2 | Element / Matrix | federated secure messenger | федерация, E2EE, комнаты, клиенты, история, развитая экосистема |
| 3 | Zulip | self-hosted threaded team chat | уникальная модель topics, web/mobile/desktop, импорт, администрирование, быстрые релизы |
| 4 | Rocket.Chat | self-hosted team chat | каналы, роли, omnichannel, приложения, marketplace, администрирование |
| 5 | Softros LAN Messenger | коммерческий LAN messenger | простой LAN-onboarding, шифрование, группы, файлы, история, корпоративные политики |
| 6 | BeeBEEP | open-source LAN messenger | peer-to-peer LAN, GUI, группы, файлы, история, zero-server сценарий |
| 7 | Squiggle | open-source LAN messenger | LAN discovery, GUI, групповой чат, файловый обмен, но проект менее активен |
| 8 | LAN Messenger | open-source LAN messenger | похожая ниша LAN-чата, GUI и базовые функции, но устаревшая активность |
| 9 | JavaNetworkChat | educational TCP chat | работает как демо TCP/Java/Swing, но не конкурентно как пользовательский продукт |

Вывод: `JavaNetworkChat` входит в рейтинг только как учебный baseline. В реальном продуктовом
рейтинге он находится ниже зрелых LAN/team chat решений, потому что пока не закрывает минимальный
набор ожиданий конечного пользователя.

## Что изучено в changelog лидеров

Источники:

- Mattermost changelog: https://docs.mattermost.com/product-overview/mattermost-v11-changelog.html
- Zulip 12.0 release: https://blog.zulip.com/2026/04/27/zulip-12-0-released/
- Rocket.Chat release notes: https://docs.rocket.chat/docs/rocketchat-release-notes
- Element Web releases: https://github.com/element-hq/element-web/releases
- BeeBEEP site/downloads: https://www.beebeep.net/
- Squiggle releases: https://github.com/hasankhan/Squiggle/releases
- Softros LAN Messenger: https://messenger.softros.com/
- LAN Messenger: https://lanmessenger.github.io/

Общие паттерны релизов лидеров:

- они регулярно улучшают onboarding и администрирование, а не только сетевую часть;
- много внимания получают безопасность, permissions, auditability, privacy и обновления зависимостей;
- релизы содержат исправления UX-регрессий, performance и стабильности клиентов;
- пользовательские сценарии расширяются итерационно: история, поиск, файлы, уведомления, настройки;
- changelog пишется не только для разработчиков, но и для операторов/пользователей.

## Где JavaNetworkChat уступает лидерам

- Нет discovery: пользователь вручную вводит адрес и порт.
- Нет persistent history: сообщения пропадают после закрытия клиента.
- Нет комнат, каналов, приватных сообщений и ролей.
- Нет файлового обмена.
- Нет TLS, E2EE, аккаунтов, паролей или токенов.
- Нет reconnect/retry и нормального lifecycle клиента.
- GUI слишком минимален: нет send button, labels, status bar, settings screen, message timestamps.
- Нет отображения собственных сообщений после отправки.
- Список пользователей не показывает самого пользователя и не сортируется.
- Нет упаковки в installer/native image.
- Нет протокольной совместимости/версирования frames.
- Нет observability: метрик, health endpoint, понятных runtime logs для пользователя.

## UI/UX тестирование

Проверено:

- автоматический `uiTest` из Gradle;
- ручной запуск server + bot;
- ручной protocol flow с подключением пользователя и командой боту;
- статический аудит Swing-кода: `ChatWindow`, `ClientGuiController`, `ChatClient`, `ClientGuiModel`.

### Найденные баги и UX-дефекты

1. **Критично: cancel/blank в диалоге порта не закрывает сценарий подключения.**
   `ChatWindow.getServerPort()` при `null` или blank делает `continue`, поэтому пользователь не может
   отменить настройку порта обычным способом.

2. **Критично: ошибки handshake скрываются как generic "Нет соединения".**
   Дубликат имени, invalid username и server busy приходят как `ERROR`, но GUI не показывает точную
   причину и не предлагает повторить ввод.

3. **Высокий приоритет: пользователь не видит собственные отправленные сообщения.**
   Сервер рассылает `TEXT` всем, кроме отправителя. Для GUI это выглядит как потерянное сообщение.

4. **Высокий приоритет: список пользователей не показывает текущего пользователя.**
   Новый клиент получает только других участников, а собственная идентичность нигде в GUI не видна.

5. **Высокий приоритет: нет reconnect/retry после ошибки подключения.**
   После неудачи поле ввода остается disabled, но понятного действия для пользователя нет.

6. **Средний приоритет: startup flow нелогичен.**
   Сначала спрашивается имя, потом адрес/порт. Ожидаемый порядок: сервер/порт, имя, connect.

7. **Средний приоритет: нет defaults.**
   Для локального запуска должны быть значения `localhost` и `1500`.

8. **Средний приоритет: нет send button.**
   Отправка только по Enter неочевидна.

9. **Средний приоритет: длинное сообщение больше `ChatMessage.MAX_DATA_LENGTH` может бросить
   unchecked exception из UI action.**
   Нужно валидировать длину до отправки и показывать ошибку в интерфейсе.

10. **Средний приоритет: пользователи хранятся в `HashSet`, порядок списка нестабилен.**
    В GUI нужен сортированный список.

11. **Средний приоритет: `EXIT_ON_CLOSE` завершает JVM вместо управляемого disconnect.**
    Для desktop-клиента лучше закрывать socket, останавливать worker thread и затем dispose.

12. **Низкий приоритет: Gradle `runClient` плохо подходит для scripted stdin.**
    Проверка через pipeline завершилась на вводе имени. Нужно явно проверить interactive stdin и при
    необходимости задать `standardInput = System.in` для `JavaExec` задач.

13. **Низкий приоритет: UI smoke test создает два `ChatWindow`.**
    `new ClientGuiController(false)` уже создает view, затем тест создает второй `ChatWindow`.

## Следующий релиз: v1.1.1 Bugfix UX Stabilization

Статус: реализовано в текущем worktree как первый bugfix-срез roadmap.

Цель: исправить найденные баги без добавления крупных продуктовых функций.

Изменения:

- Добавить единый `ConnectionSettings` dialog: server address, port, username, кнопки Connect/Cancel.
- Значения по умолчанию: `localhost`, `1500`.
- Cancel должен закрывать клиент или возвращать в начальное состояние без бесконечных циклов.
- Показывать точную ошибку handshake: duplicate username, invalid username, server busy, timeout.
- Добавить retry после ошибки подключения.
- Добавить send button рядом с input field.
- После отправки показывать собственное сообщение локально со статусом `sending/sent` или изменить
  серверный protocol flow так, чтобы sender тоже получал подтвержденное сообщение.
- Показывать текущего пользователя в status bar: `Вы: alice`.
- Сортировать список пользователей.
- Валидировать blank и overlong сообщения в GUI до вызова `ChatMessage.text`.
- При закрытии окна выполнять graceful disconnect.
- Исправить `uiTest`, чтобы он не создавал лишнее окно.
- Проверить `runClient`/`runGuiClient` задачи и добавить `standardInput = System.in`, если это нужно
  для корректного интерактивного запуска.

Критерии приемки:

- `./gradlew.bat check` проходит.
- GUI позволяет отменить подключение без зависания.
- Дубликат имени показывает понятный текст ошибки и позволяет попробовать другое имя.
- Отправитель видит свое сообщение.
- Список пользователей отсортирован и текущая identity видна.
- Длинное сообщение не ломает EDT, а показывает UX-ошибку.

## Патчноуты на пять версий после bugfix

### v1.2.0 Connection Experience

Статус: реализовано в текущем worktree после `v1.1.1`.

Цель: сделать первый запуск похожим на реальный desktop messenger.

- Добавлен стартовый экран подключения вместо цепочки `JOptionPane`.
- Добавлены сохранение последних `host`, `port`, `username` в локальные preferences.
- Добавлен status bar: connection state, текущий пользователь, число участников.
- Добавлены retry/backoff при временной сетевой ошибке.
- Добавлен read-only режим при потере соединения с кнопкой Reconnect.
- Добавлены tests на cancel, retry, invalid port, duplicate username.

Acceptance:

- новый пользователь подключается к локальному серверу без чтения README;
- после рестарта клиента последние настройки восстановлены;
- любые ошибки подключения дают понятное действие.

### v1.3.0 Message Timeline

Статус: реализовано в текущем worktree после `v1.2.0`.

Цель: улучшить базовый опыт переписки.

- Добавлена модель сообщений вместо хранения только `newMessage`.
- В GUI отображаются timestamp, sender, own/remote alignment и service events.
- Добавлена локальная история текущей сессии.
- Добавлен copy/select all и очистка локальной истории.
- Добавлен лимит визуальной ленты и тесты на большие сообщения.
- Protocol `messageId` используется для дедупликации/ack own message.

Acceptance:

- пользователь видит полную ленту текущей сессии;
- собственные сообщения не дублируются;
- USER_ADDED/USER_REMOVED оформлены как service events, а не как обычный текст.

### v1.4.0 Rooms And Private Messages

Статус: реализовано в текущем worktree после `v1.3.0`.

Цель: перейти от одного общего чата к минимально полезной chat-модели.

- Добавлены комнаты: `general` по умолчанию и создание комнат серверной командой/API.
- Добавлен список комнат в GUI.
- Добавлены private messages между пользователями.
- Расширен protocol с versioned message types.
- Добавлены integration tests для room join/leave, room broadcast и private delivery.
- README и architecture обновлены под новый protocol contract.

Acceptance:

- сообщение комнаты получают только участники комнаты;
- private message получает только адресат;
- старые клиенты получают явную protocol error вместо silent failure.

### v1.5.0 Persistence And Search

Статус: реализовано в текущем worktree после `v1.4.0`.

Цель: сделать чат полезным после перезапуска клиента/сервера.

- Добавлено серверное хранение истории в SQLite или file-backed storage.
- Добавлена загрузка последних N сообщений при входе в комнату.
- Добавлен поиск по локально загруженной истории.
- Добавлена ротация/лимит хранения.
- Добавлены экспорт истории в JSON/CSV.
- Добавлена миграция старых JSONL storage records и тесты восстановления после restart.

Acceptance:

- после рестарта сервера последние сообщения доступны новым клиентам;
- поиск работает по sender, тексту и дате/timestamp;
- storage corruption обрабатывается понятной ошибкой и не ломает запуск.

### v1.6.0 Secure And Packaged Messenger

Статус: реализовано в текущем worktree после `v1.5.0`.

Цель: подготовить проект к использованию за пределами учебного запуска из IDE.

- Добавлен TLS mode для server/client через JSSE keystore/truststore.
- Добавлена account-модель: username + salted PBKDF2-HMAC-SHA256 token hash.
- Добавлены роли: `USER`/`ADMIN`.
- Добавлен package build: Windows zip с `.bat` launchers.
- Добавлены release checksums и provenance metadata.
- Добавлены structured logs, server health command `/health` и troubleshooting guide.
- Обновлен `SECURITY.md` с threat model для chat-протокола.

Acceptance:

- пользователь может скачать артефакт релиза и запустить без Gradle;
- TLS включается конфигурацией;
- release artifacts имеют checksums;
- security-документация описывает границы доверия и известные ограничения.
