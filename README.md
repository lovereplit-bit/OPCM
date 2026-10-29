<p align="center">
  <a href="https://t.me/OpenCodeMob">
    <picture>
      <source srcset=".github/assets/banner-dark.svg" media="(prefers-color-scheme: dark)">
      <img src=".github/assets/banner-light.svg" alt="OpenCode">
    </picture>
  </a>
</p>

Опенсорсный ИИ-агент для кодинга. Целиком на твоём Android-телефоне — без root, без настройки, без внешних серверов.

**Наше сообщество: [Telegram](https://t.me/OpenCodeMob)**

[![Telegram](https://img.shields.io/badge/Telegram-OpenCodeMob-2AABEE?style=flat-square&logo=telegram&logoColor=white)](https://t.me/OpenCodeMob)
[![Releases](https://img.shields.io/github/v/release/lovereplit-bit/OPCM?style=flat-square)](https://github.com/lovereplit-bit/OPCM/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/lovereplit-bit/OPCM/build-apk.yml?style=flat-square&branch=main)](https://github.com/lovereplit-bit/OPCM/actions/workflows/build-apk.yml)

---

### Установка

| Платформа          | Скачать                                                            |
| ------------------ | ------------------------------------------------------------------ |
| Android 8.0+ (arm64) | `opencode-*.apk` из [Releases](https://github.com/lovereplit-bit/OPCM/releases/latest) |

> [!TIP]
> APK лёгкий (~70 МБ): Linux-окружение с сервером OpenCode (~300 МБ) скачивается при первом запуске и обновляется без переустановки приложения.

Первый запуск всё делает сам: скачивает окружение (около минуты), поднимает сервер и сразу открывает чат. Разреши уведомления — иначе Android усыпляет сервер в фоне.

#### Автонастройка

Приложение само создаёт всё, что раньше вводили руками:

- **localhost** — сервер `opencode serve` работает на телефоне в Ubuntu 24.04 (arm64) через `proot`, без root;
- **пароль** — 24 случайных символа, генерируются один раз и хранятся на устройстве;
- **ник** — дружелюбное имя вроде `swift-otter-42`, оно же имя профиля подключения.

```
Приложение Android (React Native + Kotlin-модуль) ── чат, терминал, файлы, голос
              │                         ▲
              ▼                         │ http://127.0.0.1:4096 + пароль
        Фоновая служба ── proot ── Ubuntu 24.04 arm64 (скачивается при первом запуске)
                                ├─ opencode serve
                                ├─ node, npm, python3, uv, git, ripgrep, curl
                                └─ инструкции для агента в /etc/opencode-android/AGENTS.md
```

### Агенты

Как в настольном OpenCode, встроены два агента — переключение в один тап:

- **build** — основной агент с полным доступом для работы над кодом
- **plan** — агент только для чтения: анализ и исследование кодовой базы
  - по умолчанию запрещает правки файлов
  - спрашивает разрешение перед запуском команд
  - удобен для изучения незнакомого проекта или планирования изменений

Также доступен субагент **general** для сложных поисков и многошаговых задач — вызывается через `@general` в сообщениях.

Подробнее про [агентов](https://opencode.ai/v2/docs/agents).

### Возможности

- **Работает сразу, без входа.** Ни экрана логина, ни ручного ввода адреса и пароля: первый запуск открывает чат. Бесплатные модели Zen доступны анонимно; свой API-ключ из консоли OpenCode вставляется одним вводом в Настройки → Локальный сервер.
- **Весь функционал OpenCode Mobile.** Сессии и агенты, выбор моделей и авто-выбор по сложности, терминал, файлы и патчи, голосовой ввод, разговорный режим, виджеты на домашнем экране, уведомления о задачах, несколько подключений.
- **Интерфейс в стиле OCM.** Монохромная серая палитра `#FAFAFA` / `#161616`, квадратные углы, иконка и аватар OpenCode, звуки `attention`, `quota` и `task_done` на события агента.
- **Русский язык.** Полный перевод интерфейса; всего 9 языков.
- **Работа в фоне.** Сервер живёт в foreground-службе: удержание процессора и Wi-Fi, уведомление о состоянии.

### Документация

Про настройку самого OpenCode (модели, провайдеры, правила агентов) — [**документация OpenCode**](https://opencode.ai/v2/docs). Про приложение — этот README и код репозитория.

### Сборка APK

Всё собирается автоматически workflow **Build APK** (`.github/workflows/build-apk.yml`):

- **каждый пуш в `main`** собирает и публикует новый релиз;
- **тег `v*`** (например, `v2.0.0`) собирает релиз с этим тегом;
- **ручной запуск** — вкладка Actions → Run workflow (можно без публикации).

Workflow сам: собирает свежий сервер OpenCode из [upstream](https://github.com/anomalyco/opencode) на arm64-раннере, упаковывает Ubuntu-окружение, скачивает proot из репозитория Termux, собирает подписанный APK и выкладывает релиз с тремя файлами:

- `opencode-*.apk` — приложение;
- `opencode-runtime.tgz` — Linux-окружение, которое приложение скачивает при первом запуске;
- `opencode-runtime.json` — манифест рантайма (версия, размер, sha256).

**Ключ подписи уже в коде**: `android-signing/release.keystore` вместе с паролями зашит в `scripts/build-android-release.mjs`. Свежий checkout собирает подписанный APK без каких-либо секретов. Захочешь свой ключ — положи другой keystore и переопредели переменные `ANDROID_KEYSTORE_*` в окружении.

> Ключ, зашитый в репозиторий, виден всем, у кого есть доступ к репозиторию. Для личной сборки и раздачи знакомым это нормально; для публичного магазина заведи отдельный ключ и держи его в секретах.

### Локальная сборка

```bash
npm ci
npm run typecheck
npm test

# положить proot-библиотеки для запуска сервера (нужен patchelf)
bash runtime/fetch-proot.sh modules/opencode-server/android/src/main/jniLibs/arm64-v8a

# собрать подписанный APK
npm run build:android
```

### Структура

```
app/                 экраны (expo-router): чат, проекты, терминал, настройки, автосетап
components/          UI-компоненты, в том числе секция «Локальный сервер»
lib/local-server.ts  JS-мост к нативному серверу: localhost + пароль + ник
modules/opencode-server/  нативный модуль (Kotlin): proot, служба, установка рантайма
runtime/             сборка Linux-окружения: build-opencode.sh, build-rootfs.sh, fetch-proot.sh
android-signing/     keystore для подписи APK (вшит в код)
.github/workflows/build-apk.yml   CI: APK + рантайм → Releases
```

### Участие в разработке

Нашёл баг или хочешь фичу — открой issue или пришли в [Telegram](https://t.me/OpenCodeMob). Пул-реквесты приветствуются: сначала опиши задачу в issue, потом делай ветку от `main`.

### Building on OpenCode

Этот проект не создан командой OpenCode и никак с ней не связан. OPCM — самостоятельный community-клиент: основа — [opencode-mobile](https://github.com/alvarolorentedev/opencode-mobile) (Apache-2.0), дизайн, звуки и механика локального сервера — [OCM / OpenCodeMob](https://github.com/lovereplit-bit/OCM) (MIT), сам сервер — [OpenCode](https://github.com/anomalyco/opencode) (MIT). Условия распространения proot и Termux — в [NOTICE](NOTICE).

---

**Присоединяйся к сообществу: [Telegram](https://t.me/OpenCodeMob)**
