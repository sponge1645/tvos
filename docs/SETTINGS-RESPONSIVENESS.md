# Settings transition responsiveness

Opening Control Center used `return` immediately after changing the screen ID.
This skipped the common focus reset, `invalidate()` and accessibility update at
the end of activation. Once the previous focus animation had settled, the new
page could wait for an unrelated event before appearing. Category and Dock
editors had the same early-return path. They now finish through the shared
navigation tail. Regression tests execute all three production activation paths
and require an immediate redraw request.

The first Control Center frame also resolved the VPN application label through
PackageManager on the drawing thread. TV-provider resolution and About's device,
display and network reads had similar paths. `SettingsValues` now requests typed
snapshots from `SettingsValueLoader`; Canvas and accessibility reads return a
cached value or a short placeholder. Platform access is confined to the worker.
Device-name edits remain visible immediately without repeating network queries.

The loader has one shared worker, a 32-job queue and at most 16 entries per cache.
Repeated frames share a pending request. A full queue keeps a stable fallback
instead of resubmitting on every frame. Pausing cancels queued reads and discards
late delivery. The worker exits after 30 idle seconds. Locale and target package
are part of label keys; returning to Settings refreshes snapshots.

Tests cover the missing-redraw regression, 1,000 reads of a pending value producing
only one worker request, UI-only result delivery, cancellation, eviction, overload,
platform failures and device snapshots. Actual TV frame timings still need to be
checked on the device; this change removes identified blocking paths and does not
claim a measured FPS gain.
