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
