# Profile avatars

18 Apple emoji images, downloaded during the build from the external pack and embedded for offline use, arranged as two pages of nine. The first page order is robot, fox, woman, frog, popcorn, pig, panda, videocassette, clapper board. Page two contains monkey, dog, cat, lion, rabbit, hot dog, man, Statue of Liberty, poo.

Source catalog: https://github.com/dherault/react-apple-emojis/blob/master/src/data.json (Apple set 419, downloaded 2026-10-24). Original PNG URL is `https://em-content.zobj.net/source/apple/419/` followed by the catalog filename. Images were resized to at most 192×192 pixels using Lanczos and encoded as transparent WebP (quality 90). Emoji artwork retains its respective rights; the project's code license does not grant rights to Apple artwork.

Gradient colors are drawn on Canvas separately from the images. Only the visible page remains in the profile bitmap cache after transitions settle; both pages can be retained briefly during a crossfade. The clock loads only its selected avatar. Names remain accessible to screen readers but are not shown beneath avatars. Existing seven-avatar preferences are mapped to matching new images.

DPAD right at the rightmost column opens the next page; left at the leftmost column opens the previous page. Page dots also accept touch/accessibility activation. Back cancels selection.

The current source branch stores only the asset pack URL, hashes and downloader; avatar bytes are staged in `out/assets/avatars` and cached in the ignored `.tvos-assets` directory. See ASSETS.md. Older Git commits may still contain the original files.
