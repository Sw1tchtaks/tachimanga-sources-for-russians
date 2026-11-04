# Tachimanga Sources for Russians

Русскоязычные источники манги для [Tachimanga](https://tachimanga.app/) (iOS). Они также подходят для других приложений, которые понимают расширения Tachiyomi.

## Как добавить в Tachimanga

1. Откройте **Настройки → Расширения → Репозитории расширений** (значок пазла).
2. Нажмите **Добавить** и вставьте адрес:

```
https://raw.githubusercontent.com/Sw1tchtaks/tachimanga-sources-for-russians/repo/index.min.json
```

3. Вернитесь в **Расширения** и установите нужные источники.

## Источники

| Источник | Сайт | 18+ | Вход в аккаунт |
| --- | --- | --- | --- |
| Senkuro | senkuro.com / senkuro.me | нет | не нужен |
| Senkognito | senkognito.com | да | не нужен |
| ReadManga | a.zazaza.me | нет | логин и пароль в настройках или WebView |
| AllHentai | 20.allhen.online | да | логин и пароль в настройках или WebView |
| MangaBuff | mangabuff.ru | частично | WebView |
| InkStory | inkstory.net | нет | WebView или токен |

Ранобэ (RanobeHub, Ранобэ.рф, Ranobes) Tachimanga не поддерживает: приложение показывает только картинки, а не текст.

## Вход в аккаунт

Во всех источниках настройки устроены одинаково (**Расширения → источник → ⚙️**):

- **ReadManga и AllHentai** — включите «Автоматический вход» и введите логин и пароль от сайта. Это самый простой способ.
- **Остальные** — откройте источник, нажмите значок глобуса 🌐 (WebView), войдите на сайте и закройте окно. Приложение запомнит вход.
- **Запасной вариант** — вставьте строку Cookie из браузера (или токен для InkStory) в настройках источника.

Если сайт переехал, выберите другое зеркало или впишите новый адрес в поле «Свой домен».

## Для разработчиков

Сборка: `./gradlew assembleRelease`, затем `python3 scripts/make_repo.py repo`. При каждом изменении в `main` GitHub Actions сам собирает расширения и публикует их в ветку `repo`.

Общий код лежит в `lib-common` (настройки, вход, зеркала) и `lib-multisrc` (Senkuro, Grouple). Каждый источник находится в `src/ru/<имя>`, а его версия и метаданные — в `ext.json`. После изменения источника увеличьте `versionCode`.

Часть кода адаптирована из [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source) (Apache 2.0), подробности в `NOTICE`.
