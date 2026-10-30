# Я смотрю

Источник — системная таблица Android TV Watch Next. Поддерживаются любые приложения,
которые публикуют в неё карточки с доступной ссылкой открытия. Включите публикацию
«Продолжить просмотр» в настройках каналов сервиса и откройте этот раздел
в приложении. tvOS не авторизуется в аккаунтах сервисов и не читает его приватные файлы.

При первом выборе «Я смотрю» tvOS запрашивает `android.permission.READ_TV_LISTINGS`.
На прошивках без подходящего диалога разрешение можно выдать через ADB:

```sh
adb shell pm grant io.tvos android.permission.READ_TV_LISTINGS
```

Удержание ОК на заголовке «Я смотрю» открывает меню выбора сервиса. KinoPub,
Seena и Lampa показываются только при наличии установленного приложения; для KinoPub
достаточно оригинала или MOD. Другие издатели карточек добавляются автоматически.
Пункт «Все» закреплён внизу и отмечен галочкой в общем режиме; выбор одного сервиса
снимает эту галочку. Выбор сохраняется в настройках.

Раздел показывает до 10 последних уникальных фильмов/сериалов выбранного сервиса.
В режиме «Все» действует отдельная квота 10 на каждый сервис, общий список
отсортирован по времени последнего просмотра. Оригинальный KinoPub и MOD считаются
одним сервисом. Сортировка — по времени последней активности, объединение
эпизодов KinoPub — по ID произведения. Для других издателей используется тип и
название в пределах сервиса. Метаданные и оставшееся время берутся из Watch Next, время округляется
вверх до минуты; неизвестная длительность не заменяется выдуманным значением.

Открывается опубликованная приложением ссылка с исходными параметрами продолжения.
Возобновление на нужной позиции зависит от реализации этой ссылки в KinoPub.
Постеры поддерживают HTTPS, content URI и Android resource URI при наличии доступа.
У присланного MOD ссылка содержит сезон, эпизод и позицию в `kino_resume`.
Список обновляется при возвращении на главный экран и изменениях провайдера;
наблюдение прекращается, когда главный экран скрыт. Случайная карточка не добавляется.

Проверка на ТВ: порядок и отсутствие дублей, обе страницы по пять карточек,
подписи фильма/сериала, открытие на сохранённом эпизоде, обновление после просмотра,
отказ/выдача разрешения и отключение/перемещение раздела в настройках категорий.

Для Seena tvOS запрашивает инициализацию каналов при возвращении на Home и выборе
Seena/«Все», если приложение установлено и раздел включён. Запросы объединяются
и отправляются не чаще одного раза в 90 секунд; пропущенный повторный запрос
откладывается, не сбрасывая очередь. Сама Seena начинает публикацию с задержкой
45 секунд и требует доступа к сети. Ограничение прошивки на системный broadcast
обрабатывается через встроенный ADB. Номер задания JobScheduler не закрепляется.

## Dock backgrounds

When every poster category is disabled, focusing a Dock app for 450 ms loads a static landscape image from that service’s published TV channels. New movies take priority, followed by recommendations and movie channels; Watch Next is a fallback when no landscape candidates are published. KinoPub and its mod share a service identity. Portrait-only publishers (including Seena) can resolve a landscape backdrop via an exact TMDB poster/title/year match. Ambiguous names are rejected. A localized PNG title logo is preferred, with a published-title caption below the left-side header as fallback. Provisional results contain no title while logo lookup is pending; final text waits 700 ms. Captions fade out before the replacement fades in, and the waiting period schedules a single wakeup instead of continuous frames. Apps without suitable artwork keep the gradient background. The last Dock background stays visible while navigating the upper controls, lower actions and weather widget. Dock photos have no black scrims. Bundled settings and YouTube/TizenTube backgrounds load locally before any provider or network query, using the same focus debounce and fade. These full compositions have no additional title/logo caption.

Queries, downloads and decoding run on a single worker. Focus changes invalidate stale results and disconnect their active image/TMDB connection off the UI thread, so the next service does not wait for the previous socket timeout. Landscape lookup and logo lookup are separate: a matched background can appear before the logo endpoint completes. A sixteen-entry metadata LRU retains only URLs and identifiers, avoiding repeated searches when revisiting an app; provisional photo cache entries still retry unfinished caption lookup. Images are sampled to at most 1920 × 1080 before composing the background; only the current image and transition are retained, with six downloaded files (16 MiB each maximum) on disk. Reduced Motion skips the crossfade. Device memory and frame timing still need measurement on TVs; image quality depends on what each app publishes.

Dock navigation gives incoming banners opposing motion with a 600 ms cubic ease-out during the existing 800 ms fade. Panoramic bundled assets start at their full left/right source edge and settle centered, using the entire side margin at a constant scale. Ordinary movie images retain their subtle 32-pixel drift. Ordinary 16:9 movie artwork uses a temporary four-percent enlargement to cover all edges, then settles to its original full composition. Caption enrichment does not restart motion; interrupted fades retain the outgoing image position. Reduced Motion disables both drift and fade.

Published original-resolution image.tmdb.org artwork is requested at w1280. Published-image sockets use a 2.5-second connect/read timeout before trying the TMDB fallback; metadata and fallback downloads retain their existing timeout. The tvOS-DockBackdrop log reports elapsed time to the published image, background and final caption without logging content URLs. There is no timer-based banner rotation: a load takes the first usable title in channel priority/order, reuses the current package result, and queries again after a different app has replaced it or Home resumes. A provider changing its published order can therefore change the next loaded title.
