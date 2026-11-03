package io.tvos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.tvos.setup.SetupState;
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
    private boolean automatic, updates;
    private LinearLayout body;
    private TextView regionText;
    private SetupWelcomeView welcome;
    private AutoRegion.Result detected;
    private String[] manual;
    private String regionStatus = "";
    private String tvPackage;
    private String autoTvLabel = "";
    private Button autoTvChoice, tvChoice;
    private final List<String[]> tvApps = new ArrayList<>();
    private String text(int id) { return AppStrings.get(id); }
    private SharedPreferences settings() { return getSharedPreferences("settings", MODE_PRIVATE); }
    private SharedPreferences setup() { return getSharedPreferences("setup", MODE_PRIVATE); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); Ui.init(this); SetupGate.restart();
        if (getIntent().getBooleanExtra("replay", false)) {
            getIntent().removeExtra("replay"); setup().edit().putBoolean("complete", false).putInt("step", 0).apply();
        }
        setup().edit().putBoolean("started", true).apply();
        if (!settings().contains("language")) Prefs.setLanguage(SetupState.language(getResources().getConfiguration().getLocales().get(0).getLanguage()));
        page = SetupState.step(setup().getInt("step", 0));
        automatic = settings().getBoolean("region_auto", true); updates = settings().getBoolean("auto_update", true);
        tvPackage = settings().getString("live_tv_app", "");

    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("replay", false)) {
            intent.removeExtra("replay"); page = SetupState.HELLO; SetupGate.restart();
            setup().edit().putBoolean("complete", false).putBoolean("started", true).putInt("step", page).apply();
            if (active) render();
        }
    }
    @Override protected void onResume() { super.onResume(); active = true; render(); }
    @Override protected void onPause() { active = false; cancelWork(); stopHello(); super.onPause(); }
    @Override protected void onDestroy() { cancelWork(); io.shutdownNow(); super.onDestroy(); }
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
    private TextView label(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(0xFFF5F5F7);
        text.setGravity(Gravity.CENTER); text.setPadding(dp(12), dp(8), dp(12), dp(8));
        body.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }
    private Button button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setTextSize(19); b.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(dp(60), dp(5), dp(60), dp(5));
        body.addView(b, params); b.setOnClickListener(v -> action.run()); return b;
    }
    private Button nextButton;
    private void render() {
        cancelWork(); stopHello(); regionText = null;
        if (page == SetupState.HELLO) {
            welcome = new SetupWelcomeView(this, this::next); setContentView(welcome); welcome.requestFocus(); return;
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF142C45, 0xFF080B14}));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER);
        body.setPadding(dp(80), dp(20), dp(80), dp(20)); scroll.addView(body); setContentView(scroll);
        label("tvOS", 23);
        switch (page) {
            case SetupState.LANGUAGE:
                label(text(R.string.settings_setup_language), 34); label(text(R.string.settings_setup_language_hint), 19);
                for (AppLanguage language : AppLanguage.values()) button((Prefs.language().equals(language.code) ? "✓ " : "") + language.nativeName,
                        () -> { Prefs.setLanguage(language.code); detected = null; regionStatus = ""; render(); }); break;
            case SetupState.REGION:
                label(text(R.string.settings_region), 34);
                label(text(R.string.settings_setup_region_hint), 18);
                label(text(R.string.settings_help_auto_region), 16);
                button((automatic ? "✓ " : "") + text(R.string.settings_automatic), () -> { automatic = true; detected = null; regionStatus = ""; render(); });
                button((!automatic ? "✓ " : "") + text(R.string.settings_manual), () -> { automatic = false; regionStatus = ""; render(); searchCity(); });
                regionText = label("", 22); updateRegionText();
                if (automatic && detected == null) detectCity(); break;
            case SetupState.WIFI:
                label(text(R.string.settings_setup_wifi), 34); label(text(R.string.settings_setup_wifi_hint), 21);
                label(text(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        ? R.string.settings_setup_permission_granted : R.string.settings_setup_permission_optional), 19);
                button(text(R.string.settings_setup_allow), () -> {
                    settings().edit().putBoolean("wifi_ssid_permission_requested", true).apply();
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 5041);
                }); break;
            case SetupState.UPDATES:
                label(text(R.string.settings_setup_updates), 34); label(text(R.string.settings_setup_updates_hint), 21);
                button((updates ? "✓ " : "") + text(R.string.settings_setup_updates_yes), () -> { updates = true; render(); });
                button((!updates ? "✓ " : "") + text(R.string.settings_setup_updates_no), () -> { updates = false; render(); }); break;
            case SetupState.TV:
                label(text(R.string.settings_setup_tv), 34); label(text(R.string.settings_setup_tv_hint), 20);
                autoTvChoice = button((tvPackage.isEmpty() ? "✓ " : "") + text(R.string.settings_automatic) + (autoTvLabel.isEmpty() ? "" : ": " + autoTvLabel),
                        () -> { tvPackage = ""; render(); });
                tvChoice = button(text(R.string.settings_setup_tv_choose) + (tvPackage.isEmpty() ? "" : ": " + appLabel(tvPackage)), this::chooseTv);
                tvChoice.setEnabled(!tvApps.isEmpty()); loadTv(); break;
            default:
                label(text(R.string.settings_setup_home), 34); label(text(R.string.settings_setup_home_hint), 21);
                label(text(HomeDefault.selected(this) ? R.string.settings_setup_home_selected : R.string.settings_setup_home_not_selected), 22);
                button(text(R.string.settings_setup_home_choose), () -> HomeDefault.request(this)); break;
        }
        nextButton = button(text(page == SetupState.HOME ? R.string.settings_setup_finish : R.string.settings_setup_continue), this::next);
        if (page != SetupState.HELLO) button(text(R.string.settings_setup_skip), this::skip);
        nextButton.requestFocus();
    }
    private void updateRegionText() {
        if (regionText == null) return;
        regionText.setText(automatic ? detected != null ? detected.city : regionStatus
                : !regionStatus.isEmpty() ? regionStatus : manual == null ? Prefs.city() : manual[1]);
    }
    private void detectCity() {
        regionStatus = text(R.string.settings_setup_detecting); updateRegionText();
        final String language = Prefs.language();
        task(() -> {
            android.net.ConnectivityManager manager = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (NetworkStatus.detectVpn(manager)) throw new java.io.IOException("VPN active");
            AutoRegion.Result found = AutoRegion.parse(ApkTransfer.json(
                    "https://ipwho.is/?fields=success,city,latitude,longitude&lang=" + language, () -> !active || Thread.currentThread().isInterrupted()));
            if (NetworkStatus.detectVpn(manager)) throw new java.io.IOException("VPN changed");
            return () -> { detected = found; regionStatus = ""; updateRegionText(); };
        });
    }
    private void searchCity() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(text(R.string.settings_city_name_hint));
        new AlertDialog.Builder(this).setTitle(text(R.string.settings_city)).setView(input)
                .setPositiveButton(text(R.string.settings_find_city), (d, w) -> {
                    regionStatus = text(R.string.settings_setup_detecting); updateRegionText();
                    String query = input.getText().toString().trim();
                    task(() -> {
                        List<String[]> cities = HomeData.findCity(query);
                        return () -> {
                            if (cities.isEmpty()) { regionStatus = text(R.string.settings_setup_lookup_failed); updateRegionText(); return; }
                            String[] names = new String[cities.size()]; for (int i = 0; i < names.length; i++) names[i] = cities.get(i)[0];
                            new AlertDialog.Builder(this).setTitle(text(R.string.settings_city)).setItems(names, (x, at) -> {
                                manual = cities.get(at); regionStatus = ""; updateRegionText();
                            }).setNegativeButton(text(R.string.settings_setup_back), null).show();
                        };
                    });
                }).setNegativeButton(text(R.string.settings_setup_back), null).show();
    }
    private String appLabel(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException | RuntimeException error) { return pkg; }
    }
    private void loadTv() {
        task(() -> {
            List<String[]> apps = new ArrayList<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
                if (info.activityInfo != null) {
                    String pkg = info.activityInfo.packageName; boolean seen = false;
                    for (String[] old : apps) if (old[0].equals(pkg)) { seen = true; break; }
                    if (!seen) apps.add(new String[]{pkg, String.valueOf(info.loadLabel(getPackageManager()))});
                }
            }
            apps.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
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
                if (tvChoice != null) tvChoice.setEnabled(!tvApps.isEmpty());
                autoTvLabel = detectedLabel;
                if (autoTvChoice != null) autoTvChoice.setText((tvPackage.isEmpty() ? "✓ " : "") + text(R.string.settings_automatic) + ": " + autoTvLabel);
            };
        });
    }
    private void chooseTv() {
        String[] names = new String[tvApps.size()]; for (int i = 0; i < names.length; i++) names[i] = tvApps.get(i)[1];
        new AlertDialog.Builder(this).setTitle(text(R.string.settings_setup_tv_choose)).setItems(names, (d, at) -> {
            tvPackage = tvApps.get(at)[0]; render();
        }).setNegativeButton(text(R.string.settings_setup_back), null).show();
    }
    private void next() {
        if (page == SetupState.REGION) {
            if (automatic) {
                if (detected == null) { android.widget.Toast.makeText(this, text(R.string.settings_setup_lookup_failed), android.widget.Toast.LENGTH_LONG).show(); return; }
                Prefs.setAutomaticRegion(true);
                Prefs.applyAutomaticCity(detected.city, detected.latitude, detected.longitude, Prefs.language(), Prefs.regionGeneration());
            } else {
                Prefs.setAutomaticRegion(false);
                if (manual != null) Prefs.setCity(manual[1], manual[2], manual[3], Long.parseLong(manual[4]), manual[5]);
            }
        } else if (page == SetupState.UPDATES) settings().edit().putBoolean("auto_update", updates).apply();
        else if (page == SetupState.TV) Prefs.setLiveTvApp(tvPackage);
        skip();
    }
    private void skip() {
        cancelWork(); stopHello();
        if (page == SetupState.HOME) {
            setup().edit().putBoolean("complete", true).putBoolean("started", false).remove("step").apply();
            startActivity(new Intent(this, HomeDefault.selected(this) ? HomeActivity.class : SettingsActivity.class)); finish(); return;
        }
        page++; setup().edit().putInt("step", page).apply(); render();
    }
    @SuppressWarnings("deprecation") // Android 9-compatible TV Back handling.
    @Override public void onBackPressed() {
        if (page > SetupState.HELLO) { page--; setup().edit().putInt("step", page).apply(); render(); }
        else { SetupGate.defer(); finish(); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results); if (code == 5041 && active) render();
    }
}
