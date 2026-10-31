# Ресурсы сборки

Шрифты и аватары загружаются из проверяемого пакета.
Контрольные суммы указаны в `tools/assets-pack.json`.

Для сборки используйте `./build.sh`.
Для офлайн-сборки предусмотрена переменная `TVOS_ASSETS_ARCHIVE`.

## Звуки навигации

В `app/assets/sounds` хранятся четыре коротких WAV: PCM 16 бит, 48 кГц,
стерео. `Click` заранее загружает их в SoundPool и ждёт завершения загрузки.
`move_button` используется для обычного фокуса, `move_app` для Dock,
`open` для выбора/открытия, `back` для возврата. Существующий переключатель
звуков отключает все четыре. Повторное перемещение останавливает предыдущий
звук перемещения; действия используют отдельный поток. WAV упаковываются
без сжатия контейнером APK для чтения через AssetFileDescriptor.

## Уровень Wi-Fi

`network/WifiSignal` читает только RSSI текущего соединения и подписывается на
системные изменения RSSI, пока Home видим. Сканирование сетей не запускается.
Четыре SVG `wifi_0`–`wifi_3` используют одинаковые контуры с разной прозрачностью
неактивных частей. Пороги: ниже −85 dBm, от −85, от −70, от −55. Неизвестные
или недоступные данные оставляют общий значок Wi-Fi; Ethernet и отсутствие сети
сохраняют собственные значки. Это сила радиосигнала, а не измерение скорости сети.

Complete Dock cards live in `app/assets/tiles/` (1000×600, 5:3). These eight supplied cards include their own background and logo. The app loader decodes them once at 500×300; existing rounded clipping, shadows and focus transforms render the whole card. Other brands and All Apps retain their current artwork.
