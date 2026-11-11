# Kinopoisk TV title links

Package: `ru.kinopoisk.tv`. Reviewed APK: 2.274.1. The exported
DeepLinkTargetActivity accepts ACTION_VIEW URLs under
`https://hd.kinopoisk.ru/film/<32-hex-id>`. Device validation confirmed this
opens the actual title. ACTION_SEARCH did not resolve implicitly and explicit
delivery only opened the application, so it is not used as a fallback.

Title-link data source: [JustWatch](https://www.justwatch.com/). Its public
catalogue query was tested with Dune (movie TMDB 438631) and Silver Spoon /
Мажор (series TMDB 63435). Resolve localized and original title searches, but
accept only exact TMDB ID **and** movie/show type; a title-name match is never
sufficient. Retain only canonical HTTPS hd.kinopoisk.ru film URLs. Reject
multiple different destinations. Offers are not a promise of playback rights,
subscription access or current regional availability.

The source catalogue is incomplete: a title can exist on Kinopoisk but have no
matching offer here. Missing links and network/query failures are shown as
different messages, without launching another title or the app home screen.
No user API key is needed. The query runs only on a Kinopoisk launch request.
Successful links are cached for 24 hours in a 64-entry process-local LRU;
misses/errors are not cached. Requests have 8-second connect/read timeouts, a
512-KiB response bound, no redirects and interrupt checks. Duplicate requests
are suppressed, and paused/stale UI generations cannot launch the app.
