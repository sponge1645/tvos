# Storage management — 1.2

General > Manage Storage is the seventh row, below Time Format. The controller is embedded in the existing settings window, with the same
theme, SettingsBackground,
SettingsListRenderer/SettingsRowRenderer capsules, SF Pro Display typography,
Shadows fallback and reduce-motion preference. SettingsTransitions handles the
right list slide and title crossfade in both directions. The left tile fades
tvOS out over 140 ms, then fades the app icon in over 140 ms without scaling.
Late icons wait at zero opacity until ready; changing apps uses the same
sequential opacity transition. Back fades the icon out and tvOS back in.
Reduce Motion switches immediately. The parent remains on General
row 6, so Back returns focus to the exact entry.

The list includes installed non-system apps, including disabled apps and tvOS.
System packages are excluded and tvOS has a disabled delete control. Rows sort
by total size, largest first, with stable label ordering for ties/unknown values.
Selection loads one banner/icon off the UI thread, bounded to 512 pixels, and
fades it in the left tile. The right list scrolls in nine visible rows;
Up/Down selects the delete control, OK opens confirmation, Back cancels first.
Confirmation defaults to Cancel. Touching blank space cannot delete an app.

StorageApps requests GET_USAGE_STATS through the existing local ADB connection
only when access is missing. StorageStatsManager reports appBytes + dataBytes;
dataBytes already includes cacheBytes, so cache is not added twice. Unreadable
sizes are displayed as an em dash; APK-only values are never labelled as totals.
A list-load failure supports retry with OK.

Only an explicit Delete confirmation starts uninstallation. The background
worker rechecks that the package is neither system nor tvOS, validates the
package name, and invokes pm uninstall for the launcher's Android user through
local ADB. It never removes packages from other users. Failure leaves the list
and shows a localized message; success reloads sizes and keeps the adjacent
selection visible. Leaving the settings window cancels an unsubmitted confirmation.

Validation covers real StorageApps code with simulated Android/ADB services:
statistics access, totals, unknown sizes, ordering, protected-package guards,
command validation and user scoping. Actual view input methods are tested for
confirmation, Cancel/Back focus, touch bounds and scroll visibility. Settings
routing tests assert the new row, navigation arrow and return focus. The APK
build and compiled Russian/English resources are checked separately. Real TV
visual/firmware validation remains necessary.
