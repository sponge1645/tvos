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

When every poster category is disabled, focusing a Dock app for 450 ms loads a static landscape image from that service’s published TV channels. New movies take priority, followed by recommendations and movie channels; Watch Next is a fallback when no landscape candidates are published. KinoPub and its mod share a service identity. Portrait-only publishers (including Seena) can resolve a landscape backdrop via an exact TMDB poster/title/year match. Ambiguous names are rejected. A localized PNG title logo is preferred, with a published-title caption below the left-side header as fallback. Provisional results contain no title while logo lookup is pending; final text waits 700 ms. Captions fade out before the replacement fades in, and the waiting period schedules a single wakeup instead of continuous frames. Apps without suitable artwork restore the plain gradient background. The last Dock background stays visible while navigating the upper controls, lower actions and weather widget. Dock photos have no black scrims. Bundled settings and YouTube/TizenTube backgrounds load locally before any provider or network query, using the same focus debounce and fade. These full compositions have no additional title/logo caption.

Queries, downloads and decoding run on a single worker. Focus changes invalidate stale results and disconnect their active image/TMDB connection off the UI thread, so the next service does not wait for the previous socket timeout. Landscape lookup and logo lookup are separate: a matched background can appear before the logo endpoint completes. A sixteen-entry metadata LRU retains only URLs and identifiers, avoiding repeated searches when revisiting an app; provisional photo cache entries still retry unfinished caption lookup. Images are sampled to at most 1920 × 1080 before composing the background; only the current image and transition are retained, with six downloaded files (16 MiB each maximum) on disk. Reduced Motion skips the crossfade. Device memory and frame timing still need measurement on TVs; image quality depends on what each app publishes.

Dock navigation gives incoming banners opposing motion with a 400 ms cubic ease-out during a 400 ms fade. Panoramic bundled assets start halfway between their left/right source edge and center and settle centered, using half the side margin at a constant scale. Ordinary movie images retain their subtle 32-pixel drift. Ordinary 16:9 movie artwork uses a fixed four-percent crop to cover all edges during motion and at rest; its scale never animates. Caption enrichment does not restart motion; interrupted fades retain the outgoing image position. Reduced Motion disables both drift and fade.

Published landscape candidates take precedence over portrait-only titles requiring a fallback lookup, preserving channel order within each group. Published original-resolution image.tmdb.org artwork is requested at w1280. Published-image sockets use a 2.5-second connect/read timeout before trying the TMDB fallback; metadata and fallback downloads retain their existing timeout. The tvOS-DockBackdrop log reports elapsed time to the published image, background and final caption without logging content URLs. There is no timer-based banner rotation: a load takes the first usable title in channel priority/order, reuses the current package result, and queries again after a different app has replaced it or Home resumes. A provider changing its published order can therefore change the next loaded title.

Landscape selection inspects up to four eligible channels, retaining six titles per channel before prioritizing wide artwork and reducing the final candidates to six. A full portrait-only movies channel no longer prevents Lampa recs landscapes from being considered. History channels remain excluded. Diagnostic stage timings distinguish channel queries, published image reads, TMDB matching, fallback image reads and logo lookup; no URLs or titles are logged.

Provider diagnostics also report the visible/owned channel counts, channel display names, rank and final link path segment, plus per-channel scanned/owned/accepted program counts. This distinguishes provider visibility, history filtering, empty channels and missing usable card data without logging card titles or full deep links.

Lampa resolves its title through TMDB before downloading published artwork: device logs showed repeated 2.5-second timeouts on its published image address while the matched backdrop completed quickly. A successful matched image bypasses that address entirely; missing matches or failed matched downloads still try the published landscape. Other services retain published-image-first ordering.

TMDB matching treats Russian e/yo as equivalent and accepts bilingual provider labels separated by a spaced slash. At most two individual title queries are made, original title first; year checks and ambiguity rejection remain enforced. This improves matching without claiming a logo exists for a particular movie.


Installed app icons are not drawn in the banner area. Missing artwork restores the plain background; movie captions and bundled settings/YouTube compositions remain available.

## Optional Dock resume row

General → Home Screen offers Recommendations or Continue Watching. Both apply only when every Home poster category is disabled. Recommendations keeps the service-selection landscape banner for the focused KinoPub, Seena or Lampa Dock app. Continue Watching replaces it with up to ten selectable service-specific resume cards. Enabled poster categories retain their existing Home layout regardless of this choice. Changing modes never changes category visibility/order. Up enters the wide row, Left/Right browse, Down/Back returns to Dock, Up from the row reaches top controls, and OK uses the original Watching resume intent. The row reads a service-specific snapshot without changing the Watching source. Legacy dock_continue choices remain readable until an explicit Home Screen choice is saved. Other Dock apps retain banners. Artwork lookup and bounded image prefetch run off the UI thread, retaining four active paths. Empty/unpublished service history displays the existing empty-state instruction. Seena publication refresh remains coalesced to 90 seconds. TV validation remains required for layout, navigation and third-party resume behavior.

The Dock resume heading sits above the card row at its original lower position, using 54 px bold text. The smaller 16:9 cards remain centered above Dock. Horizontal browsing uses a 360 ms decelerating translation, retaining the current offset when navigation interrupts an animation. Focus grows the selected card by five percent and lifts it six pixels over 260 ms; blur and image downloads are not repeated by this animation. Captions keep their stable baseline. Reduced Motion snaps directly to the target.

Resume cards fit within a 40 px left/right viewport, including the second full card at rest. The row waits 400 ms for the previous background to clear, then fades in over 320 ms. Decoded card images fade in over 240 ms independently, with coalesced frame callbacks only while needed; hide cancels entrance and image-frame callbacks. Reduced Motion bypasses these delays and fades.

When a provider retains a Cyrillic title while the launcher uses another language, failed requested-language matching retries Russian metadata with the same title/year/ambiguity rules. Logo preference and metadata cache identity still follow the launcher language; a Russian source-language match does not force a Russian logo in English UI.

KinoPub (original and mod), Seena and Lampa display bundled service artwork only if the movie background is still unavailable after two seconds. A background prepared before that deadline suppresses the service banner even if its logo is still loading. These panoramic assets are copied unchanged from the supplied files. A decoded movie background replaces the previous image immediately, without waiting for metadata or logo downloads. The logo enriches the same background later without restarting its transition. If no movie artwork is available, the service banner remains. Other apps retain their existing progressive loading. The Home Screen resume-card mode remains independent.

The delayed service-banner callback is cancelled on a prepared movie image, completed movie artwork, changed focus or Home pause. Cached service artwork does not bypass the delay when revisiting an app. The two-second timer starts with the focus-debounced loading request, not after network work finishes.

## Recovery and provider boundaries

An automatic-region request may temporarily disconnect a configured Incy VPN;
TV traffic uses the direct connection during this pause. Before disconnecting,
tvOS syncs a private restore-intent file and requires successful scheduling of a
persisted recovery job in the main process. Normal completion and the local timer
also attempt restoration. Home/Settings startup, boot and package replacement
request recovery for unfinished intents. Cross-process file locking prevents
concurrent restore attempts from toggling a reconnected VPN off. Failed recovery
retains the intent, retries, and displays a notice. Job execution is best effort:
firmware restrictions, force-stop, missing Incy or network failure can delay
reconnection, and a later manual VPN change cannot always be distinguished.

Watching observes only Watch Next, channels and preview programs. Observer-driven
reloads have a five-second minimum interval and coalesce changes without
indefinitely delaying them during continuous updates. Explicit Home/permission
refreshes remain immediate. Other publishers cannot be identified reliably from
change notifications alone, so these relevant tables are still re-read.

Published content images must resolve through PackageManager to the publishing
package. Resource URIs must name that package; HTTPS CDN images remain supported.
Missing, foreign or unavailable content providers are rejected. No new URI grants
are requested. APK download trust is documented in APK-SOURCES.md.
