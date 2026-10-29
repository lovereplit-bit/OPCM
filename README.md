<p align="center">
  <a href="https://t.me/OpenCodeMob">
    <picture>
      <source srcset=".github/assets/banner-dark.svg" media="(prefers-color-scheme: dark)">
      <img src=".github/assets/banner-light.svg" alt="OpenCode — настоящий OpenCode на Android-телефоне">
    </picture>
  </a>
</p>
<p align="center">Настоящий OpenCode на Android-телефоне. Работает сразу, ничего настраивать не нужно.</p>
<p align="center">
  <a href="https://github.com/lovereplit-bit/OCM/releases"><img alt="Releases" src="https://img.shields.io/github/v/release/lovereplit-bit/OCM?style=flat-square" /></a>
  <a href="https://github.com/lovereplit-bit/OCM/actions/workflows/build-apk.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/lovereplit-bit/OCM/build-apk.yml?style=flat-square&branch=main" /></a>
</p>

---

**OpenCode 2.0** — это OpenCode Mobile (от [alvarolorentedev/opencode-mobile](https://github.com/alvarolorentedev/opencode-mobile)) с интерфейсом, звуками, иконками и аватаркой из [OCM](https://github.com/lovereplit-bit/OCM), который сам создаёт себе localhost, пароль и ник. Никаких экранов настройки подключения: первый запуск сам скачивает Linux-окружение, поднимает сервер OpenCode прямо на телефоне и сразу открывает чат.

### Установка

| Платформа | Скачать |
| --- | --- |
| Android 8.0+ (arm64) | APK из раздела Releases этого репозитория |

APK лёгкий (~70 МБ): Linux-окружение с сервером (~300 МБ) скачивается при первом запуске и обновляется без переустановки приложения.

Первый запуск распаковывает окружение (около минуты) и запускает сервер. Приложение попросит разрешения на уведомления — иначе Android усыпляет сервер в фоне.

### Как это устроено

Приложение само создаёт всё, что раньше вводили руками:

- **localhost** — сервер `opencode serve` запускается на телефоне в Ubuntu 24.04 (arm64) через `proot`, без root;
- **пароль** — 24 случайных символа, генерируются один раз и хранятся на устройстве;
- **ник** — дружелюбное имя вроде `swift-otter-42`, оно же имя профиля подключения.

Профиль подключения создаётся автоматически: `http://127.0.0.1:4096` + сгенерированный пароль + ник. Подключаться к внешним серверам и Cloud Link по-прежнему можно из настроек.

```
Приложение Android (React Native + Kotlin-модуль) ── чат, терминал, файлы, голос
              │                         ▲
              ▼                         │ http://127.0.0.1:4096 + пароль
        Фоновая служба ── proot ── Ubuntu 24.04 arm64 (скачивается при первом запуске)
                                ├─ opencode serve
                                ├─ node, npm, python3, uv, git, ripgrep, curl
                                └─ инструкции для агента в /etc/opencode-android/AGENTS.md
```

### Возможности

- **Работает сразу, без входа.** Ни экрана логина, ни ручного ввода адреса и пароля: первый запуск открывает чат. Бесплатные модели Zen доступны анонимно; свой API-ключ из консоли OpenCode вставляется одним вводом в Настройки → Локальный сервер.
- **Весь функционал OpenCode Mobile.** Сессии и агенты, выбор моделей и авто-выбор по сложности, терминал, файлы и патчи, голосовой ввод, разговорный режим, виджеты на домашнем экране, уведомления о задачах, несколько подключений.
- **Интерфейс в стиле OCM.** Монохромная серая палитра `#FAFAFA` / `#161616`, квадратные углы, иконка и аватар OpenCode, звуки `attention`, `quota` и `task_done` на события агента.
- **Русский язык.** Полный перевод интерфейса; всего 9 языков.
- **Работа в фоне.** Сервер живёт в foreground-службе: удержание процессора и Wi-Fi, уведомление о состоянии.
- **Свой API-ключ по желанию.** Персональный ключ из консоли OpenCode — в Настройки → Локальный сервер; после перезапуска сервера запросы идут под вашей квотой.

### Сборка APK в GitHub Actions

Всё собирается автоматически workflow **Build APK** (`.github/workflows/build-apk.yml`):

- **каждый пуш в `main`** собирает и публикует новый релиз;
- **тег `v*`** (например, `v2.0.0`) собирает релиз с этим тегом;
- **ручной запуск** — вкладка Actions → Run workflow (можно без публикации).

Workflow сам: собирает свежий сервер OpenCode из [upstream](https://github.com/anomalyco/opencode) на arm64-раннере, упаковывает Ubuntu-окружение, скачивает proot из репозитория Termux, собирает подписанный APK и выкладывает релиз с тремя файлами:

- `opencode-*.apk` — приложение;
- `opencode-runtime.tgz` — Linux-окружение, которое приложение скачивает при первом запуске;
- `opencode-runtime.json` — манифест рантайма (версия, размер, sha256).

**Ключ подписи уже в коде**: `android-signing/release.keystore` вместе с паролями зашит в `scripts/build-android-release.mjs`. Свежий checkout собирает подписанный APK без каких-либо секретов. Захотите свой ключ — положите другой keystore и переопределите переменные `ANDROID_KEYSTORE_*` в окружении.

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

### Благодарности

- [opencode-mobile](https://github.com/alvarolorentedev/opencode-mobile) (Apache-2.0) — основа приложения: весь клиент OpenCode.
- [OCM / OpenCodeMob](https://github.com/lovereplit-bit/OCM) (MIT) — дизайн, иконки, звуки, аватар и механика локального сервера через proot.
- [OpenCode](https://github.com/anomalyco/opencode) (MIT) — сам сервер.
- proot и Termux — запуск Linux без root. Условия распространения — в [NOTICE](NOTICE).
