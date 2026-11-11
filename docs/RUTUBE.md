# RUTUBE TV search

Package: `ru.rutube.app`. The official Android TV download at
https://rutube.ru/androidtv/ provided version 31.19.0.TV-general. Its exported
SplashActivity handles VIEW intents with the rutube scheme and rutube.ru host.
The APK handler reads the `query` parameter and opens the native search screen.
The search route is `rutube://rutube.ru/search?query=<UTF-8-encoded-title>`.
It is also documented by the ATV-RU Search integration author:
https://www.slavenja.ru/atv/atvrusearch-integration-guide.pdf (page 6).

RUTUBE is offered when its TV package is launchable, in both the Watch chooser
and the default movie-service setting. Existing service indices remain stable.
A launch sends one explicit-package VIEW intent, with the title encoded as one
query value. No web browser or app-home fallback is used when the route is
refused; report the failure. No network lookup or API key is needed.

Device validation of the search screen is still required.
