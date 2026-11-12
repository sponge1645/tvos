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
Up/Down selects the delete control. OK opens the built-in Android uninstall UI
immediately; Android owns confirmation, cancellation and deletion. Touching
blank space or an app label does not open the uninstaller.

StorageApps requests GET_USAGE_STATS through the existing local ADB connection
only when access is missing. StorageStatsManager reports appBytes + dataBytes;
dataBytes already includes cacheBytes, so cache is not added twice. Unreadable
sizes are displayed as an em dash; APK-only values are never labelled as totals.
A list-load failure supports retry with OK.

The platform uninstall intent is created only for a validated installed package
that is neither system nor tvOS. REQUEST_DELETE_PACKAGES is declared for API 28+.
The selected package URI and EXTRA_RETURN_RESULT are passed to Android; there
is no custom confirmation and no ADB/pm uninstall fallback. Native uninstallation
works independently of the ADB connection used for statistics. Returning from
the system UI reloads sizes and restores the same app on cancellation or the
adjacent row after successful removal. The shared list/tile transitions remain
inside the settings window.

Validation covers real StorageApps code with simulated Android/ADB services:
statistics access, totals, unknown sizes, ordering, protected-package guards,
native intent targeting and absence of silent shell deletion. Actual view input methods are tested for
native-uninstaller activation, Back focus, touch bounds and scroll visibility. Settings
routing tests assert the new row, navigation arrow and return focus. The APK
build and compiled Russian/English resources are checked separately. Real TV
visual/firmware validation remains necessary.
