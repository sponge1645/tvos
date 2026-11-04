# 1.1.2 maintenance audit — 2026-11-04

Scope: cold forecast opening, poster downloads, compressed artwork retention,
decoded bitmap limits, unused assets, and the complete JVM regression suite.
This is source-level verification; it does not establish TV frame times or PSS.

## Changes

- Warm forecast SVG paths on a shared background worker, once per process.
  Prepare the lightweight forecast dialog and date formatters shortly after Home
  resumes; cancel unused preparation when Home pauses. Reuse time formatters
  during rain/snow frames instead of allocating them every frame.
- Default poster instances share three workers instead of creating an executor
  for every instance. Search retains two lifecycle-owned workers. One failing
  request no longer serializes every Home poster behind a 20-second timeout.
- A single retry timer per poster-cache instance retries visible failed paths
  after 30 seconds. Hidden windows do not poll or reload. New draw requests are
  no longer required to recover from a temporary network/decode failure.
- Poster, Dock and backdrop writers use unique partial files. Two screens loading
  the same URL cannot truncate or remove each other's partial download.
- Read image bounds before poster decode. Sampling respects the 12 MiB cache
  budget divided by the active window, with at most 2 MiB per decoded image.
  Stop/rejected executors cannot throw into drawing code.
- The shared `ArtworkDiskCache` applies compressed-byte and file-count LRU limits,
  preserves recent hits, and removes crash leftovers older than one hour while
  leaving live partial files alone.
- Backdrop downloads reject empty, truncated and over-16-MiB responses. Backdrop
  decoding bounds dimensions, and an OOM no longer deletes a valid cached image.
- Remove the retired SVG wordmark, its drawing tests, the unused Scene argument
  of the forecast dialog, duplicate plain-background shaders and duplicated disk
  eviction implementations.
- Correct two regression expectations left at the previous 50 px top margin;
  production layout remains at the requested 40 px.

## Cache policy

| Cache | Compressed disk budget | Additional limit |
| --- | --- | --- |
| Posters (Home, search, resume row) | 200 MiB shared directory | 12 MiB decoded RAM per instance; hidden windows release RAM |
| Dock / resume artwork backgrounds | 96 MiB | 96 files; decoded RAM ownership unchanged |
| Recommendation backdrops | 96 MiB | 48 files; existing decoded LRU unchanged |

The three directories have a combined compressed-file ceiling of 392 MiB,
excluding active temporary downloads. They do not expire solely because of age:
least recently used files are removed only when a budget is exceeded. Disk hits
work offline. Android storage reclamation, manual Clear Cache, new uncached URLs
and unavailable publisher images still require network access.

## Structure and follow-up

No unreferenced top-level Java class was found by name/reference scanning.
This check cannot prove that every branch or dynamic resource is reachable.
`HomeView` and `SettingsActivity` remain large coordinators; further extraction
should target one responsibility at a time with navigation/lifecycle tests.
`SetupArtwork` is mostly vector data, so its line count alone is not a reason to
rewrite it. The disk policy extraction above removes actual duplication now.

Regression coverage includes concurrent partial writers, automatic retry without
redraw, hidden-window cancellation, large-dimension sampling, stopped workers,
byte/count LRU, current partial preservation and forecast-symbol warmup.
Real-device checks: first forecast opening after a cold start, fast paging while
offline, recovery after restoring connectivity, Android trim-memory behavior,
GPU smoothness and disk/PSS measurements. Android compilation is verified by CI
separately from JVM graphics/HTTP adapters.
