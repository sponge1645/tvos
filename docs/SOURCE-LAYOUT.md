# Source layout

Source directories follow Java packages. The first isolated modules are:

- `io.tvos.watching`: service aliases and source identities.
- `io.tvos.artwork`: publisher image URI policy, independent of the Android adapter.
- `io.tvos.network`: durable VPN restore journal, independent of Android scheduling.

Android adapters and existing screens still live in `io.tvos`. Move cohesive
modules as their dependencies are made explicit, rather than making all existing
package-private classes public or relocating files while retaining a flat package.
Resources keep Android's standard `res/values*` locale layout. The build discovers
Java files recursively. Tests compile the actual package paths.

- `io.tvos.settings`: stable screen IDs and navigation policy, ordered-item and Dock
  editor models, cached settings background, modal snapshot renderer and update text
  layout. Models receive storage interfaces/callbacks; they do not import Prefs,
  HomeApps or Android. SettingsEditors adapts the private launcher preferences.
  SettingsContent remains the localized-text adapter; SettingsActivity owns input,
  focus and screen lifecycle.
- `io.tvos.version`: numeric version comparison and build display identity.

The current testing display is `1.1.2 (1B161)`. Major version is numeric; minor version uses A=0, B=1, …, Z=25, AA=26. The three-digit sequence is maintained explicitly in `version/SoftwareBuild.java` and is not incremented by a local rebuild. Keep it increasing across patch versions within a major/minor series. APK versionName and OTA tags remain numeric; Android installation ordering still uses versionCode, which must increase for releases.

Brand presentation mappings and rating badge caches now live in `artwork/` alongside image source policy.

- `io.tvos.updates`: validated release metadata and pinned release download URLs. The Android updater remains an adapter in the original package.

Settings app queries run through `SettingsAppLoader` with lifecycle cancellation.
The settings background is static; only active focus, scroll, title, page and modal
animations request additional frames. General no longer opens system date/time
settings; date visibility and the 12/24-hour preference remain independent.

Home Screen mode in General chooses service recommendation banners or KinoPub/Seena/Lampa resume cards only when every poster category is disabled. It never changes category visibility/order or the Watching source.

- `io.tvos.home`: shared layout constants/math, top-control focus policy and
  the one-shot dream-return timeline. These helpers have no Android, preferences
  or renderer dependencies. HomeView caches policy callbacks once, shares menu
  host behavior and delegates nearest-card lookup to one implementation. The
  obsolete ready-only label Paint, write-only timestamps and zero-delay reveal
  scheduling were removed. Source application removes backed-up root copies of
  relocated Home classes.

- `io.tvos.home.dock`: generic Dock edit transaction, clock-driven caption
  transitions and resume-row rendering. The editor receives a save callback;
  HomeApps maps app items to persisted identities. The renderer receives fonts,
  labels, colors, bitmaps and a shadow callback, without reading preferences or
  loading provider data. DockContinue owns history/artwork/key handling and
  DockBanner adapts artwork into the independent caption timeline. The unused
  resume-row noWidgets argument was removed. Relocated editor sources are removed
  after backup when applying snapshots.

The artwork package now owns PosterCache, RoundedImages and the ImagePainter
boundary. Cache warnings are injected; rounded drawing receives the owning
platform's fixed legacy policy. Shared image code does not depend on Ui,
RouletteRenderer, Recs or AppLog. HTTPS and content/resource streams use one
bounded/cancellable copy routine, retaining existing cache paths, limits,
atomic promotion, retry timing and stale-window checks. Regression coverage
includes provider oversize/null streams and cancellation during copying.

Settings presentation: `settings/SettingsTextRenderer` owns tracked glyph drawing and the single wrapped-description cache; `settings/SettingsLogoRenderer` owns cached tile/cutout geometry. The Activity supplies text, theme, scale and typeface and clears description state on pause/resize. Both renderers are independent of preferences, localization and screen IDs. Cache keys include exact scale and typeface, not only rounded width. Removed obsolete title/subtitle/subrow/warning/spinner Paint allocations from SettingsActivity.

Settings rows: `settings/SettingsRowRenderer` owns capsule/focus geometry, reusable Paint/blur state and the cached chevron path; a shadow callback retains the existing legacy cache adapter. Drawing never mutates logical label/focus bounds. `SettingsTextRenderer` shares tracked-width measurement and one fitted-value cache including width, text size, typeface and tracking; pause/resize clears it. SettingsActivity retains screen policy and application data, without duplicate row graphics state or the unreachable About-row background branch.

`settings/SettingsListViewport` owns the isolated clipped list layer and its
resize-dependent edge mask. Its content callback receives only Canvas; screen
selection and transition state remain in SettingsActivity. The callback is retained
once by the view, with no per-frame callback or mask allocation.

VPN recovery records each started attempt in the existing durable marker under the
shared process/file lock. Five attempts are allowed across restarts; only attempts
one through four schedule a silent retry. Exhaustion removes the intent and emits
one localized manual-recovery notice. Legacy one-byte markers start at zero.

`settings/SettingsTransitions` owns menu/title transition state and focus delay.
The caller supplies screen-parent policy, a screen painter, localized title,
geometry, time and accessibility options. It retains navigation, focus springs
and frame scheduling. There is no duplicate transition state in SettingsActivity;
callbacks are allocated once. The existing parent-dependent direction, 280ms
smoothstep, title-only crossfade and fixed background/logo geometry are preserved.
Four obsolete list geometry constants and the viewport forwarding method were
removed after checking source, resources, reflection and tooling references.

`settings/SettingsListWindow` owns the logical offset, directional window movement,
picker positioning, editor focus-following and buffered draw range. It takes row
counts and indices without Android, preferences, screen IDs or app data. Focus
and the existing Spring animations remain in SettingsActivity; the old offset
field is removed. Shared Up/Down handling preserves key-repeat sounds, key-up
accessibility callbacks and About's read-only behavior. Row bounds share one
calculation, retaining About's detail offset and animated accessibility geometry.

`settings/SettingsRemoteInput` owns OK/Back press state and key-event sequencing.
Four callbacks expose vertical movement, horizontal adjustment, confirmation and
Back; they carry no launcher data or lifecycle access. The Activity retains screen
policy, click sounds, TalkBack gating and update-dialog dispatch. Pause resets the
input component; no duplicate held-key flags remain. Horizontal row routing uses
one invalidation after an actual edit, preserving repeat and release behavior.

`settings/SettingsScreen` now also owns parent hierarchy, fixed row counts and
menu chevron policy. SettingsActions uses that same hierarchy for Back targets;
return-row rules and unknown/legacy fallbacks are preserved. SettingsActivity
supplies live app/editor counts and the automatic-region flag without duplicating
screen structure. Removed unused errorLayout/errorShown fields and their obsolete
StaticLayout import after checking source, resources, reflection and tooling.

SettingsPreferenceEditor is the platform storage adapter for value mutations:
General and legacy values, weather visibility/migration, date and dream source.
It receives SharedPreferences and change/redraw callbacks, with no Activity,
Canvas or navigation state. The existing Prefs backend stays inside the root
adapter package. Notification order, defaults and persisted keys are unchanged.
General and legacy theme/unit changes share one allocation-free cycle helper;
the dream-source choice array is retained rather than rebuilt per adjustment.

SettingsTextRenderer now also owns shared row label/value placement, service value
fitting, picker label fitting/checkmark reservation and Dock label ellipsizing.
The Activity supplies strings, bounds, baseline and Paint; screen/data policy and
decorations stay outside the renderer. Picker and value fitting share width and
Unicode truncation logic but retain distinct narrow-width behavior: picker keeps
an ellipsis when it cannot fit, values may become empty. That policy participates
in the existing single-value cache. Dock retains TextUtils and its 520px limit;
its measure Paint moved from the Activity into the renderer, with one setup per
list draw. About detail values retain their original two-step right offset.

Settings structural checkpoint (1B144):

- `settings/SettingsListRenderer` owns complete row presentation, palette,
  decorations, active-last ordering and shared menu/scroll/accessibility geometry.
  It reads no Activity, Prefs, HomeApps, Ui, screen IDs or application data. One
  immediate Row input is reset/reused per draw; it is not a second navigation or
  selection model. Check-symbol metrics/drawing and update-spinner drawing are
  narrow graphics callbacks. Menu/editor decoration ordering, disabled VPN
  fitting, About detail bounds and the three checkmark recipes remain distinct.
- `SettingsModeMonitor` owns the HomeMode snapshot, pending-switch timing,
  polling cadence, completion notices and observed region-entry state. The View
  supplies its existing running/region predicates and notification callbacks;
  there is no second running flag. Pause stops polling, resets region entry and
  retains the previous busy state exactly as before.
- `SettingsValues` also owns installed-version/last-check text and its cache.
  The View retains only bindings: navigation/focus and picker data, localization
  selection, platform launch/prompt effects, input adapters and lifecycle/frame
  coordination. Those are intentional Android adapter responsibilities, not
  further independent renderers or generic models to be split for line count.

Regression coverage executes the list renderer and every View row-binding path,
including active-last/overscan, all row/marker modes, themes, 720p/1080p/4K,
spinner frame ownership, read-only About, disabled VPN and legacy screens. Mode
monitor tests cover both clocks, exact timeout limits, reboot/stale timestamps,
notice delivery across pause, region visits and stale-callback cancellation.

`home/HomeGeometry` owns Dock, drawer/weather, tabs, action-button and poster
bounds. It takes existing output rectangles plus order/visibility, labels,
fonts and dimensions; it imports no View, Activity, Prefs, HomeApps or Ui and
stores no selection/navigation state. HomeView retains cache invalidation and
dirty flags. Clock measurement reuses one Paint/FontMetrics pair while reading
current text, typeface and scale on every call. The pure HomeLayout constants/math
remain Android-independent. HomeView retains the original seven redraw layers
and two search-position buffers rather than allocating them on every use.
Validation covers 720p/1080p/4K, Dock counts 0–8, drawer groups, compact layout,
all 720 action orders x 64 visibility masks, live clock/font changes, measurement
object reuse, search coordinates and exact invalidation/lifecycle guards.

`HomeAppWork` is the application adapter for Dock/drawer/All Apps IO and VPN
commands. It receives the application Context, existing shared Executor, Handler
and small delivery callbacks. It owns delivery generation and VPN command busy
state; MovieAppLauncher reads the same generation, and pause invalidates it.
Queued IO is not cancelled or reordered. Delivery checks current activity and
generation on the UI queue, while HomeView retains sheet visibility and binding.
A VPN completion always clears busy state, including stale or inactive results;
only current active failures display the existing localized notice.

`home/HomePosterWindow` handles page bounds, page item identity and prefetch
priority (current, next two, previous). It has no Android or application-type
dependencies and stores no page/selection state. Unchanged item references reuse
the current page list; new localized objects replace it even when IDs match.
Changed pages remain independent mutable snapshots. HomeView retains focus,
animations, catalog selection and image requests. Regression tests execute both
components with controlled worker/UI queues and exhaustive page/window cases.

`HomeDockState<T>` now owns the Dock item list, selected/lifted indices, focus and
slide springs, rectangles, lift spring and wiggle origin. Key comparison is an
injected predicate; the component imports no Activity, Prefs, HomeApps or Ui.
Refreshing identical keys replaces app objects while retaining animation
identities. Reorder/rollback keep item focus, velocity and animated screen
position together. HomeDockEditor still owns the edit transaction and persistence;
HomeView coordinates renderer caches, layout, input, sound and accessibility.
The unused write-only `dockAnimating` flag was removed.

`HomeGridState<T>` owns All Apps items, selection, sheet/open state, focus and
scroll springs. It handles row-bounded navigation and scroll targeting without
Android Views, application types, preferences or UI callbacks. HomeView supplies
column/visible-row counts and applies feedback only when selection changes.
Data replacement retains the original focus-reset policy; opening/closing and
lifecycle resets use the same model state, with no parallel View copies.

`home/HomeLayerMotion` applies drawer/sheet/dream-return transforms to retained
Views from current values and dimensions. It does not schedule frames, retain
selection/progress copies or depend on Activity/Prefs/HomeApps/Ui. The existing
HomeReveal remains the sole reveal clock. Opacity clamps and translation
overshoot stay distinct; transform geometry, visibility thresholds, pivot setup
and cached-layer redraw policy remain unchanged. Unused grid geometry aliases
were removed from HomeView; renderer/accessibility geometry stays in HomeLayout.
Regression coverage executes full models and View adapters, Dock edits including
multi-move rollback, all grid indices/arrows at counts 0–67, stale refresh guards,
TalkBack bindings, key handling, and dream/overlay transforms at 720p/1080p/4K.

`HomeDrawerState<T>` owns drawer entries, the leading input count, selection,
focus/geometry arrays, weather bounds, extra height and opening spring. Entry
identity and input classification are injected predicates; it has no HomeApps,
Activity, Prefs or Ui dependency. Same-count refresh retains focus/rectangle
identities even when keys or connection state change, unlike Dock's deliberately
different rebuild policy. Opening/closing snap focus exactly as before. HomeView
keeps geometry calls, cross-region nearest-item navigation and invalidation.

`HomeSearchState<T>` is the sole owner of search mode, retained query, results and
heading spring. It distinguishes first entry from input reopening, preserves query
on close, and owns heading intent/fades without Android input-window dependencies.
SearchController retains debounce, request generations and pause/resume retry;
HomeView retains page/index and navigation effects. The shared View refresh helper
keeps typing's focus behavior distinct from delivered-result and screen transitions.

`HomeSearchGlass` owns input-backing bounds, the two position buffers, shadow
Paint/filter, drawing and dismissal transform cleanup. It receives retained Views,
Scene/Shadows and current scale/night; it does not consult Activity, Prefs or Ui.
Repeated resize at the same scale reuses its blur filter. The View retains active/
input visibility guards and tone updates. Blending now uses the existing
ActionSurface implementation, removing HomeView's duplicate blend and unused tone
helpers. The redundant roulette guard after currentCatalog's early return was
removed. No keys, input timing, material parameters or lifecycle policy changed.
Tests execute complete drawer/session/backing components with actual View adapters:
0–10 drawer entries, leading groups, same-count/shrink/connection changes, remote
and TalkBack routing, search debounce/stale callbacks/Done/reopen/heading reversal,
backing coordinates/material/night colors, retained resources and 720p/1080p/4K.

### Weather parsing and city lookup (1B149)

`weather/WeatherForecast` is the shared mutable forecast snapshot, preserving Celsius units,
NaN/code defaults and mutable hourly lists. `weather/WeatherParser` decodes Open-Meteo with
an injected clock. Its date formatter is local to each response, preserving lenient parsing,
response/device timezone behavior and at most six strictly future hours in provider order.
The clock is sampled once after required hourly arrays are read, as before.
`weather/CityGeocoder` receives a text transport and explicit language search order; it owns
search labels/deduplication, coordinate formatting, stable-ID verification and legacy ID
migration. It avoids the temporary admin/country array per result.

`HomeData` remains the Android adapter: preferences and cache keys, refresh/retry deadlines,
HTTP transport, worker scheduling, settings epochs, stale-response guards, localization
persistence and listener delivery stay there. Home, weather widgets, accessibility and both
screensavers consume the same forecast type. The city and widget adapters compile against
the real new components; parser coverage includes DST, lenient dates, hourly boundaries,
clock timing, malformed responses and concurrent response isolation.


### Recommendation selection and diagnostics (1B150)

`catalog/RecommendationSelection<T>` owns the ordered catalog filter (limit 25), hourly
rotation (up to three distinct franchises) and device-local morning/afternoon/night
boundaries. Kind/title readers and the clock are injected; lists retain original item
instances and no catalog state is duplicated. `Recs` retains API-key gating, fresh versus
popular source selection, tab/type-ID deduplication, showcase image readiness and trim.
The wanted-tab order is reused instead of allocating a new kind array on every call.

`catalog/RecommendationStatus` writes the existing `recs-status.json` snapshot with the
same count/error/at fields and UTF-8 output; Recs retains failure handling and refresh
scheduling. The unreferenced `Recs.status(Context)` reader and its private stream-copy
helper/imports were removed after checking app sources, resources, manifest, tools,
documentation and tests. The diagnostic file continues to be written, with no settings
or cache migration. Selection regressions execute the new component through actual
Recs adapters; diagnostic checks use real files and exercise overwrite/failure recovery.


### Interactive TMDB query policy (1B151)

`catalog/TmdbQueries<T>` owns multi-search pagination, suffix fallback, ordered type/ID
replacement and acclaimed discover paging/deduplication. It takes JSON transport, live
language and a three-operation metadata mapper; page randomness is injectable. The query
component has no Android, preferences, UI or launcher-model dependency. The search word
pattern is compiled once and the query is trimmed once within the component.

`Recs` supplies its existing metadata decoder, owns the interactive queue and UiRequest,
and shares the worker/error/UI-delivery path between search and roulette. Existing
operation-specific logging, partial results on failures and cancellation before transport,
after each response and before UI delivery are retained. Search null-argument validation
remains outside the network-failure path. The now-unused private add wrapper is removed.
The existing complete-page 36-item threshold is retained: a page can take the result over
36 before the next-attempt check. No new ranking, strict limit or result ordering is added.

Regression checks execute the real generic policy and actual asynchronous Recs adapters:
URLs/live locale, two-page cap, suffix order/termination, malformed rows, replacement
order, partial transport/mapper failures, queued/in-flight/late cancellation, random page
bounds, ID marking before mapping, complete-page threshold and existing error handling.


### Dock provider and image boundaries (1B152)

`watching/DockPrograms` reads recommendation channels/programs into candidate title,
year, poster and landscape URLs. It retains stable channel priority, exclusion of history,
four-channel/six-candidate bounds, Watch Next fallback and publisher/service aliases.
Provider/resource authorization is an injected predicate receiving the publishing package;
title-only candidates remain eligible for metadata fallback. Fixed query projections are
reused, without new arrays on each channel read.

`artwork/DockImageLoader` owns HTTPS/provider/resource reading, atomic part-file publication,
16 MiB limit, six-file cache and sampled landscape/logo decode. A small request-lifetime
interface preserves token checks and explicit connection open/close ownership, without
per-image callback allocation. Existing cache names/paths, timeout selection, aspect checks,
corrupt-file recovery and eviction behavior remain intact. URLs are authorized by provider
or metadata adapters before entering the loader.

`DockBackdrop` retains focus debounce, generation/cancel state, permission request, service
banners, two-second loading deadline, metadata resolution, staged photo/caption delivery
and UI notification. Full-loader tests still execute those adapters, now with real service
aliases. Independent loader tests use actual temp files and controlled streams/connections
for cancellation, size limits, sampling, cache hits/eviction and partial cleanup.

Deferred product work is recorded in `docs/DEVELOPMENT-NOTES.md`; volume-key hold is
explicitly deferred and no accessibility key handling changed in this pass.


### History row and resume boundaries (1B153)

`WatchingCardReader` adapts Android rows to the existing Recs.Pick model, including source
identity, movie/episode inference, remaining time, portrait authorization and KinoPub's
resume JSON/URI fallbacks. It remains an Android/model adapter in the root package;
common intent policy does not depend on launcher preferences or the card model.
Numeric item/path checks reuse a compiled pattern, preserving ASCII-digit semantics.

`watching/ResumeIntentPolicy` parses provider links, verifies publishing-package ownership
and removes selector/clip/grant flags while preserving the original URI and extras.
`watching/HistoryChannels` discovers exact Seena and Lampa history channels, retaining
Seena insertion order, Lampa continued-before-history merging and null-cursor early return.
The channel projection is reused; no titles, timestamps or playback positions are invented.

`WatchingCatalog` retains permission/observer lifecycle, worker scheduling, coalescing,
request generations, history program reads/ranking, source selection and UI delivery.
Complete-catalog regressions compile all extracted production components and also exercise
intent cleanup, ownership/size limits, channel matching/order and cursor lifecycle directly.
The existing per-source quotas, row-error boundaries and history refresh behavior remain.


### Structural baseline and final audit (1B154)

The broad package/component separation phase is complete for the current feature set.
See `docs/REFACTORING-AUDIT.md` for the ownership map, verified dead-code cleanup and
remaining device-validation work. HomeView and SettingsActivity remain Android UI
coordinators; their size alone is not a reason to introduce additional Host interfaces
or duplicate navigation state. Future extraction should follow a concrete independently
owned responsibility, not a target file/line count.


### Strict JDK 21 settings regressions (1B155)

The Russian locale uses Java-8/Android-compatible `Locale.forLanguageTag("ru-RU")`
rather than the Locale constructor deprecated on newer JDKs. The shared test RectF
constructor assigns bounds directly instead of dispatching to an overridable set method;
a subclass probe verifies this behavior. Both affected settings scripts keep
`-Xlint:all -Werror`. A separate CI job runs those two regressions on JDK 21, while
the full suite and strict Android APK build remain on JDK 17. The structural baseline
and app language/date/API-tag behavior are unchanged.

The shared `Paint` stub also uses a private copy helper so its public copy
constructor does not dispatch to an override under JDK 21.


### Opt-in volume-key diagnostics (1B156)

RemoteKeyService logs Android volume down/up/repeat metadata and the actual filter
result only when `log.tag.tvOS-Volume=DEBUG`. It does not consume, replay or adjust
volume. The raw Linux traces were identical with Accessibility enabled/disabled;
Android-layer traces are required before choosing a repeat fallback.


### Expanded key-filter diagnostics (1B157)

The same opt-in `tvOS-Volume` tag now records all Android key codes (including
source), plus service connection/build/flags, unbind, destruction and interruption.
The 1B156 volume-only trace remained empty on the user's TV although a shell log
probe was visible and the service was bound. Recording arrows alongside volume
will distinguish a silent service boundary from unexpected firmware key codes.
No key text, event injection, volume changes or service-flag changes are added.


### Integrated opt-in volume-hold compatibility (1B158)

`remote/VolumeHoldStore` owns atomic cross-process preference/status files (default
off). `remote/VolumeHoldRunner` owns one serialized connection, cancellation generations
and a bounded retry burst; Android bindings stay in `RemoteVolume`, owned by the
existing `RemoteKeyService`. No remote app-key policy or IDs change. Settings add
one footer row after the five existing assignments, with live localized connection
status and a signature-protected cross-process notification. Only status/preference
transitions touch storage; drawing and onKeyEvent do not read the helper/ADB stream.

The separate `tools/volume-hold/VolumeHoldDevice.java` shell artifact is built by
build.sh into out/assets/volume-hold.jar before resource packaging. It is not part
of the app class loader. The app transfers the APK-bundled artifact over its existing
loopback ADB client and starts it in quiet audio mode; no runtime download or new
signing identity is introduced. The shell owns its temporary directory and cleanup.
One file lock prevents duplicate helpers. The helper waits while idle, observes raw
volume only, keeps the native first step and targets the tested 400/50 ms timing.
A six-second/100-step hold guard and heartbeat/EOF handling bound lost-release or
connection failures. App-key accessibility filtering stays unchanged. See
DEVELOPMENT-NOTES.md for measured prototype results and remaining device checks.


### Final audit and bounded cleanup (1B159)

See FINAL-AUDIT.md for the feature ledger, verification scope and remaining device
checks. Fix accessibility-feedback interruption so the enabled volume listener
reconnects rather than staying paused. Use explicit network/ConnectionObserver
open/close callbacks with connection identity for TMDB/Dock; setup/disconnect
failures release tracking and stale opens/closes cannot replace/clear a new one.
PosterRenderer reuses two independent marquee fade states (title and metadata),
invalidating geometry/edge changes and releasing them on clear. Remove the
test-only DockArtwork.find wrapper and retired host-per-step volume prototype/
its duplicate test. The current standalone device runner remains a build tool
and diagnostic; production depends on its compiler. No further broad splitting.

### Experimental remote bindings and system volume (1B161, branch 1.1.2)

remote/RemoteBindings owns bounded atomic identity/scope/action storage.
remote/TapSequence owns scheduler-injected single/double/triple/hold state.
RemoteShortcuts adapts Android events and cancellation in Home and :keys;
RemoteShortcutActions executes a fixed action allowlist.
RemoteShortcutSettingsActivity owns capture/editor dialogs. SettingsActivity
adds one entry while retaining all five legacy assignments and the volume toggle.
SystemVolume is an opt-in privileged long-press listener: no DUMP polling or
global hidden-API policy changes. The existing bounded ADB helper remains default.
