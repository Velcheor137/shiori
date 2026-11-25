# Shiori

**Shiori** (栞 — «закладка») — неофициальный Android-клиент для чтения ранобэ с [RanobeLib](https://ranobelib.me).

> Shiori не связан с RanobeLib/LibSocial и не одобрен ими. Приложение работает с вашим аккаунтом RanobeLib напрямую,
> без собственного сервера: все запросы идут с устройства на API LibSocial.

## Возможности

- Вход в свой аккаунт RanobeLib на странице сайта (пароль не проходит через приложение).
- Каталог с поиском и сортировкой, карточка тайтла, список глав с переводчиками как на сайте.
- Закреплённый перевод: приложение никогда само не переключает переводчика.
- Читалка: полный экран, гибкие настройки (шрифты, интервалы, цвета, обводка, яркость), копирование текста.
- Режим текста «Оригинал / Литературный»: реплики в кавычках оформляются тире, мысли — «ёлочками»; исходный текст не меняется.
- Комментарии всех переводов главы в одной ленте, ответы, оценки.
- Скачивание глав по диапазонам (`1-100, 155, т3`) и чтение офлайн.
- Закладки в тексте, «Продолжить чтение», тёмная и светлая темы.

## Установка

Скачайте `shiori-X.Y.Z.apk` и `shiori-X.Y.Z.apk.sha256` из [Releases](https://github.com/Velcheor137/shiori/releases) и проверьте файл:

```bash
sha256sum -c shiori-X.Y.Z.apk.sha256
```

Нужен Android 8.0 или новее.

## Сборка из исходников

Нужны JDK 21 и Android SDK (платформа 37.2).

```bash
./gradlew assembleDebug
```

Тесты:

```bash
./gradlew testDebugUnitTest test
```

Подписанная release-сборка локально:

```bash
SHIORI_KEYSTORE_PATH=/путь/к/release.jks SHIORI_KEYSTORE_PASSWORD=… SHIORI_KEY_ALIAS=… SHIORI_KEY_PASSWORD=… \
  ./gradlew assembleRelease -PreleaseVersion=0.4.0
```

## Релизы

Релиз собирается GitHub Actions по тегу `vX.Y.Z` (или `vX.Y.Z-beta.N`) на ветке `main`: проверки → подписанный APK →
GitHub Release с APK и SHA-256. Подробности — в [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md), §13.

## Приватность

Нет телеметрии, рекламы и сторонних SDK. Данные (прогресс, закладки, настройки, скачанные главы) хранятся только на
устройстве. Токен сессии зашифрован ключом Android Keystore. Журнал диагностики пишется на устройстве, токены в нём
маскируются, отправить его можно только вручную.

## Лицензия

Код — [GNU GPL v3.0 or later](LICENSE). Встроенные шрифты (Merriweather, Literata, PT Serif, PT Sans, Open Sans, Lora) —
SIL Open Font License 1.1, тексты лицензий в `app/src/main/assets/licenses/fonts/`.
