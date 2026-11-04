# Local avatar upload

Profile adds Upload avatar below Choose avatar. The upload page owns one local
session; Back, Activity pause and destruction close it. No cloud service, camera
permission on the TV, WebView or persistent background service is required.

The TV uses the physical Ethernet/Wi-Fi address resolved by LocalNetwork,
excluding VPN routes. One ephemeral-port TCP listener accepts one request at a
time with a small backlog. A fresh 128-bit URL token authorizes the session;
Host and browser Origin are checked. Requests have bounded headers, body bytes
and time. Sessions expire after ten minutes and accept only one successful
publication. The original cleartext outbound network policy is unchanged.

The phone page offers library/camera inputs and a draggable circular crop preview
with zoom. It rejects files above 2 MiB without displaying a numeric limit. Crop
and JPEG encoding happen on the phone; the TV independently checks the request
limit, content type, image signature, decoder bounds and actual decode. Only
JPEG, PNG and WebP are accepted by the receiver. HEIC/HEIF can be selected on
phones whose browser decodes them, then become JPEG through the canvas export.
Unsupported formats get a generic photo-selection error.

The TV decodes with a sampled pixel budget, renders a square 512x512 avatar on a
white background, re-encodes JPEG and removes input metadata. Prepared files are
published by rename in private storage only if the upload session is still open.
The previous photo and avatar selection survive invalid/cancelled uploads.
One photo is retained; the built-in avatar picker remains available. Circles are
rendered natively with a bitmap shader. No source photograph or upload history
is retained. Home reloads the custom avatar after returning from Profile.

QR generation vendors the four basic Java classes from Project Nayuki's
QR-Code-generator, MIT license, upstream commit
bcfe1b018cec5f1f91b5705eca5bb4cdd34e9f8d. The serialVersionUID addition in
DataTooLongException accommodates the project's strict Java warnings. The
license is included in APK assets/licenses/nayuki-qr.txt. QR modules are drawn
as a cached vector Path once prepared, without a QR bitmap or frame polling.

Validation follows the layered checks described in the OWASP File Upload Cheat
Sheet: https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html

Tests exercise the real HTTP listener, nonce/body length, size/type/Origin
rejection, one-shot publication and close-during-prepare race; the actual photo
store through Java2D graphics adapters; the shipped phone JavaScript through a
DOM adapter; profile arrows/Back/accessibility geometry and built-in persistence.
Android/phone checks remain necessary for LAN reachability, scanning a real TV
QR, camera/HEIC support and pointer gestures on actual phones.
