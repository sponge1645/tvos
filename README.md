# tvOS 1.0.2

Лаунчер для Android TV 9 и новее.

## Сборка

Требуются Python 3, JDK 17+ и Android SDK.

```sh
cp config.example.properties config.properties
./build.sh
adb install -r tvOS.apk
```

Для каталога фильмов укажите ключ TMDB в `config.properties`.

## Обновления

https://github.com/sponge1645/tvos/releases

Для старых версий предусмотрено обновление через 1.0.4.
Начиная с 1.0.4 используется отдельный OTA-канал.

## Документация

- [Публикация](docs/RELEASING.md)
- [OTA](docs/OTA-TESTING.md)

Лицензия: [LICENSE](LICENSE).
