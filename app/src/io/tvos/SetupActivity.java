package io.tvos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.tvos.setup.SetupState;
import io.tvos.setup.SetupCountries;
import io.tvos.setup.SetupPermissionPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Resumable opt-in first-run UI. Existing data/permissions are never silently reset. */
public class SetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    private UiRequest request;
    private int page, epoch;
    private volatile boolean active;
    private boolean permissionFlow, pendingAccessibility;
    private SetupLanguageView regionPicker;
    private String detectedCountry = "";
    private SetupWelcomeView welcome;
    private String regionStatus = "";
    private boolean homeRequested;
    private final SettingsModeMonitor homeMode = new SettingsModeMonitor(this, main, () -> active, () -> false,
            value -> android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_LONG).show(), this::homeModeChanged);
    private void homeModeChanged() {
        if (!active || page != SetupState.HOME || !homeRequested) return;
        if (homeMode.freeActive()) skip();
        else if (!homeMode.busy() && finishView != null) finishView.retryHome();
    }
    private void installHome() {
        if (homeMode.busy()) return;
        homeRequested = true; HomeMode.request(this, HomeMode.FREE); homeMode.markPending();
    }
    private String tvPackage;
    private String autoTvLabel = "";
    private SetupFinishView finishView;
    private java.util.function.Consumer<Runnable> leaveScene;
    private boolean transitioning;
    private int transitionEpoch;
    private AutoRegion.Result detectedCity;
    private String weatherStatus = "";
    private AlertDialog cityDialog;
    private final SettingsCitySearch citySearch = new SettingsCitySearch(main, () -> active,
            () -> page, this::showCityResults, this::updateWeatherText);
    private final List<String[]> tvApps = new ArrayList<>();
    private String text(int id) { return AppStrings.get(id); }
    private SharedPreferences settings() { return getSharedPreferences("settings", MODE_PRIVATE); }
    private SharedPreferences setup() { return getSharedPreferences("setup", MODE_PRIVATE); }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); Ui.init(this); SetupGate.restart();
        if (getIntent().getBooleanExtra("replay", false)) {
            getIntent().removeExtra("replay"); setup().edit().putBoolean("complete", false).putInt("step", 0).remove("home_request").apply();
        }
        setup().edit().putBoolean("started", true).apply();
        if (!settings().contains("language")) Prefs.setLanguage(SetupState.language(getResources().getConfiguration().getLocales().get(0).getLanguage()));
        page = SetupState.step(setup().getInt("step", 0));

        tvPackage = settings().getString("live_tv_app", "");

    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("replay", false)) {
            intent.removeExtra("replay"); permissionFlow = pendingAccessibility = false; page = SetupState.HELLO; SetupGate.restart();
            setup().edit().putBoolean("complete", false).putBoolean("started", true).putInt("step", page).remove("home_request").apply();
            if (active) render();
        }
    }
    @Override protected void onResume() { super.onResume(); active = true; homeMode.start();
        if (page == SetupState.HOME && setup().getBoolean("home_request", false)) {
            setup().edit().remove("home_request").apply();
            if (HomeDefault.selected(this)) { skip(); return; }
        }
        render(); if (pendingAccessibility) main.post(this::continuePermissionFlow); }
    @Override protected void onPause() { homeMode.stop(); active = false; transitionEpoch++; transitioning = false; leaveScene = null; citySearch.cancel(); dismissCityDialog(); cancelWork(); stopHello(); super.onPause(); }
    @Override protected void onDestroy() { citySearch.cancel(); dismissCityDialog(); cancelWork(); io.shutdownNow(); super.onDestroy(); }
    private void stopHello() { if (welcome != null) { welcome.stop(); welcome = null; } }
    private void cancelWork() { epoch++; if (request != null) { request.cancel(); request = null; } }
    private void task(java.util.concurrent.Callable<Runnable> work) {
        cancelWork(); final int token = epoch; UiRequest pending = new UiRequest(io); request = pending;
        pending.start(() -> {
            Runnable result;
            try { result = work.call(); }
            catch (Exception error) { result = () -> { regionStatus = text(R.string.settings_setup_lookup_failed); updateRegionText(); }; }
            final Runnable delivery = result;
            main.post(() -> { if (active && token == epoch && pending.active()) { request = null; delivery.run(); } });
        });
    }
    private void render() {
        cancelWork(); citySearch.cancel(); dismissCityDialog(); stopHello(); regionPicker = null; leaveScene = null; transitioning = false; transitionEpoch++;
        if (page != SetupState.TV) tvApps.clear();
        if (page == SetupState.HELLO) {
            welcome = new SetupWelcomeView(this, () -> { if (active && page == SetupState.HELLO) next(); }); leaveScene = welcome::leave; setContentView(welcome); welcome.requestFocus(); return;
        }
        if (page == SetupState.LANGUAGE) {
            SetupLanguageView language = new SetupLanguageView(this, () -> { detectedCountry = ""; detectedCity = null; regionStatus = ""; next(); });
            leaveScene = language::leave; setContentView(language); language.requestFocus(); return;
        }
        if (page == SetupState.REGION) {
            regionPicker = new SetupLanguageView(this, () -> { }, code -> {
                Prefs.setRecommendationRegion(code);
                skip();
            });
            leaveScene = regionPicker::leave; setContentView(regionPicker); regionPicker.requestFocus();
            if (!detectedCountry.isEmpty()) regionPicker.detectedCountry(detectedCountry);
            else detectCountry();
            return;
        }
        if (page == SetupState.WIFI) {
            SetupPermissionsView permissions = new SetupPermissionsView(this, this::allowPermissions, () -> {
                markPermissionChoice(); skip();
            });
            leaveScene = permissions::leave; setContentView(permissions); permissions.requestFocus(); return;
        }
        if (page == SetupState.WEATHER) {
            finishView = new SetupFinishView(this, SetupFinishView.Kind.WEATHER, weatherLabel(), this::chooseDetectedCity, this::askCity);
            leaveScene = finishView::leave; setContentView(finishView); finishView.requestFocus();
            if (detectedCity == null) detectWeather();
            return;
        }
        if (page == SetupState.TV) {
            finishView = new SetupFinishView(this, true, tvLabel(), this::chooseTv, this::next);
            leaveScene = finishView::leave; setContentView(finishView); finishView.requestFocus(); loadTv(); return;
        }
        finishView = new SetupFinishView(this, false, "", () -> {
            installHome();
        }, this::skip);
        leaveScene = finishView::leave; setContentView(finishView); finishView.requestFocus();
    }
    private String tvTargetLabel() {
        for (String[] choice : tvApps) if (choice[0].equals(tvPackage)) return choice[1];
        String pkg = LiveTvTargets.packageName(tvPackage);
        return pkg.isEmpty() ? tvPackage : appLabel(pkg);
    }
    private String tvLabel() {
        return tvPackage.isEmpty() ? text(R.string.settings_automatic) + ": " + (autoTvLabel.isEmpty() ? text(R.string.settings_setup_detecting) : autoTvLabel) : tvTargetLabel();
    }
    private void updateRegionText() { if (regionPicker != null) regionPicker.status(regionStatus); }
    private void detectCountry() {
        regionStatus = text(R.string.settings_setup_detecting); updateRegionText();
        final String language = Prefs.language();
        task(() -> {
            String response = locationResponse(language);
            org.json.JSONObject json = new org.json.JSONObject(response);
            String country = json.optString("country_code", "").toUpperCase(java.util.Locale.ROOT);
            if (!json.optBoolean("success", false) || !SetupCountries.valid(country))
                throw new java.io.IOException("Invalid country response");
            AutoRegion.Result city = null;
            try { city = AutoRegion.parse(response); } catch (java.io.IOException | org.json.JSONException unavailable) { /* Country can still be valid. */ }
            final AutoRegion.Result foundCity = city;
            return () -> { detectedCity = foundCity; detectedCountry = country; regionStatus = ""; if (regionPicker != null) { regionPicker.status(""); regionPicker.detectedCountry(country); } };
        });
    }
    private String locationResponse(String language) throws java.io.IOException {
        android.net.ConnectivityManager manager = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (NetworkStatus.detectVpn(manager)) throw new java.io.IOException("VPN active");
        String body = ApkTransfer.json("https://ipwho.is/?fields=success,country_code,city,latitude,longitude&lang=" + language,
                () -> !active || Thread.currentThread().isInterrupted());
        if (NetworkStatus.detectVpn(manager)) throw new java.io.IOException("VPN changed");
        return body;
    }
    private String weatherLabel() {
        return detectedCity == null ? weatherStatus : detectedCity.city;
    }
    private void updateWeatherText() {
        if (active && page == SetupState.WEATHER && finishView != null) {
            finishView.application(weatherLabel()); finishView.status(citySearch.status());
        }
    }
    private void detectWeather() {
        weatherStatus = text(R.string.settings_setup_detecting); updateWeatherText();
        final String language = Prefs.language();
        task(() -> {
            try {
                AutoRegion.Result city = AutoRegion.parse(locationResponse(language));
                return () -> { detectedCity = city; weatherStatus = ""; updateWeatherText(); };
            } catch (java.io.IOException | org.json.JSONException | RuntimeException unavailable) {
                return () -> { weatherStatus = text(R.string.settings_setup_city_failed); updateWeatherText(); };
            }
        });
    }
    private void chooseDetectedCity() {
        if (!active || page != SetupState.WEATHER || transitioning) return;
        if (detectedCity == null) { if (request == null) detectWeather(); return; }
        saveWeatherCity(detectedCity.city, detectedCity.latitude, detectedCity.longitude, 0, Prefs.language());
    }
    private void saveWeatherCity(String name, String latitude, String longitude, long id, String language) {
        if (!active || page != SetupState.WEATHER) return;
        Prefs.setAutomaticRegion(false);
        Prefs.setCity(name, latitude, longitude, id, language);
        HomeData.get(this).settingsChanged(); next();
    }
    private void dismissCityDialog() { if (cityDialog != null) { cityDialog.dismiss(); cityDialog = null; } }
    private void askCity() {
        if (!active || page != SetupState.WEATHER || citySearch.searching() || (cityDialog != null && cityDialog.isShowing())) return;
        cityDialog = SettingsCityDialog.show(this, name -> {
            if (active && page == SetupState.WEATHER && !name.trim().isEmpty()) {
                cancelWork(); weatherStatus = text(R.string.settings_setup_city_failed); citySearch.search(name);
            }
        });
    }
    private void showCityResults(List<String[]> result) {
        if (!active || page != SetupState.WEATHER) return;
        dismissCityDialog();
        final List<String[]> choices = new ArrayList<>(result);
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i)[0];
        cityDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(text(R.string.settings_city)).setItems(labels, (d, at) -> {
                    String[] city = choices.get(at);
                    saveWeatherCity(city[1], city[2], city[3], Long.parseLong(city[4]), city[5]);
                }).setNegativeButton(text(R.string.settings_cancel), null).show();
    }
    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException | RuntimeException error) { return pkg; }
    }
    private void loadTv() {
        task(() -> {
            try {
            List<String[]> apps = LiveTvTargets.load(this);
            Intent automaticIntent = HomeApps.automaticLiveTv(this);
            String pkg = automaticIntent == null ? null : automaticIntent.getPackage();
            if (automaticIntent != null && automaticIntent.getComponent() != null) pkg = automaticIntent.getComponent().getPackageName();
            if (pkg == null && automaticIntent != null) {
                android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(automaticIntent, 0);
                if (resolved != null && resolved.activityInfo != null) pkg = resolved.activityInfo.packageName;
            }
            final String detectedLabel = pkg == null ? text(R.string.settings_setup_tv_not_found) : appLabel(pkg);
            return () -> {
                tvApps.clear(); tvApps.addAll(apps);
                autoTvLabel = detectedLabel;
                if (finishView != null && page == SetupState.TV) finishView.application(tvLabel());
            };
            } catch (RuntimeException unavailable) {
                return () -> { autoTvLabel = text(R.string.settings_setup_tv_not_found); if (finishView != null && page == SetupState.TV) finishView.application(tvLabel()); };
            }
        });
    }
    private void chooseTv() {
        List<String[]> choices = new ArrayList<>(tvApps);
        String[] names = new String[choices.size() + 1];
        names[0] = text(R.string.settings_automatic) + ": " + autoTvLabel;
        for (int i = 0; i < choices.size(); i++) names[i + 1] = choices.get(i)[1];
        new AlertDialog.Builder(this).setTitle(text(R.string.settings_setup_tv_choose)).setItems(names, (d, at) -> {
            if (!active || transitioning || page != SetupState.TV) return;
            String selected = at == 0 ? "" : choices.get(at - 1)[0];
            if (!selected.isEmpty() && LiveTvTargets.intent(this, selected) == null) {
                new AlertDialog.Builder(this).setMessage(text(R.string.settings_setup_tv_unavailable))
                        .setPositiveButton(android.R.string.ok, null).show(); return;
            }
            tvPackage = selected;
            if (finishView != null) finishView.application(tvLabel());
        }).setNegativeButton(text(R.string.settings_setup_back), null).show();
    }
    private void next() {
        if (page == SetupState.TV) Prefs.setLiveTvApp(tvPackage);
        skip();
    }
    private void skip() {
        if (transitioning) return;
        permissionFlow = pendingAccessibility = false;
        cancelWork();
        if (page == SetupState.HOME) {
            int token = ++transitionEpoch;
            Runnable complete = () -> {
                if (token != transitionEpoch || page != SetupState.HOME) return;
                setup().edit().putBoolean("complete", true).putBoolean("started", false).remove("step").remove("home_request").apply();
                startActivity(new Intent(this, HomeDefault.selected(this) ? HomeActivity.class : SettingsActivity.class)); finish();
            };
            if (active && leaveScene != null) { transitioning = true; leaveScene.accept(complete); }
            else complete.run();
            return;
        }
        navigate(SetupState.next(page));
    }
    private void navigate(int target) {
        if (transitioning) return;
        int origin = page, token = ++transitionEpoch;
        Runnable show = () -> {
            if (page != origin || token != transitionEpoch) return;
            page = target; setup().edit().putInt("step", page).apply();
            if (active) render();
        };
        if (active && leaveScene != null && SetupState.backgroundTransition(origin, target)) {
            transitioning = true; cancelWork(); leaveScene.accept(show);
        } else show.run();
    }
    @SuppressWarnings("deprecation") // Android 9-compatible TV Back handling.
    @Override public void onBackPressed() {
        if (transitioning) return;
        permissionFlow = pendingAccessibility = false;
        if (page > SetupState.HELLO) navigate(SetupState.previous(page));
        else { SetupGate.defer(); finish(); }
    }
    private void markPermissionChoice() {
        settings().edit().putBoolean("wifi_ssid_permission_requested", true)
                .putBoolean("dock_backdrop_permission_requested", true).apply();
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void allowPermissions() {
        if (permissionFlow) return; permissionFlow = true; markPermissionChoice();
        boolean tvRuntime = false;
        try {
            android.content.pm.PermissionInfo info = getPackageManager().getPermissionInfo("android.permission.READ_TV_LISTINGS", 0);
            tvRuntime = info.getProtection()
                    == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { /* No TV provider on this device. */ }
        String[] pending = SetupPermissionPlan.runtime(android.os.Build.VERSION.SDK_INT,
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
                tvRuntime, granted("android.permission.READ_TV_LISTINGS"));
        if (pending.length == 0) { accessibilityPermission(); return; }
        try { requestPermissions(pending, 5042); }
        catch (RuntimeException unavailable) { accessibilityPermission(); }
    }
    @SuppressWarnings("deprecation") // Platform result API supports Android 9 without AndroidX.
    private void accessibilityPermission() {
        if (!active || page != SetupState.WIFI) { permissionFlow = false; return; }
        android.content.ComponentName wanted = new android.content.ComponentName(this, RemoteKeyService.class);
        String services = null;
        try { services = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
        catch (RuntimeException unavailable) { /* Still offer the system accessibility screen. */ }
        if (services != null) for (String service : services.split(":")) {
            if (wanted.equals(android.content.ComponentName.unflattenFromString(service))) { permissionFlow = false; skip(); return; }
        }
        try { startActivityForResult(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS), 5043); }
        catch (RuntimeException unavailable) { permissionFlow = false; skip(); }
    }
    @SuppressWarnings("deprecation") // Paired with the Android 9-compatible settings request.
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 5043 && page == SetupState.WIFI) { permissionFlow = false; skip(); }
    }
    private void continuePermissionFlow() {
        if (active && pendingAccessibility && page == SetupState.WIFI && !isFinishing()) {
            pendingAccessibility = false; accessibilityPermission();
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 5042 && page == SetupState.WIFI) {
            // Runtime callbacks may arrive before onResume after the system dialog.
            pendingAccessibility = true; main.post(this::continuePermissionFlow);
        }
    }
}
