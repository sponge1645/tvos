package io.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Explicit TV choices, including system packages without launcher icons and TV-input services. */
final class LiveTvTargets {
    private static final String ACTIVITY = "activity:", SERVICE = "service:", INPUT = "input:";
    private LiveTvTargets() { }
    @SuppressWarnings("deprecation") // Flag overloads available on Android 9.
    static List<String[]> load(Context context) {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String,String[]> choices = new LinkedHashMap<>();
        for (PackageInfo pkg : pm.getInstalledPackages(0)) {
            if (pkg == null || pkg.packageName == null) continue;
            ApplicationInfo app = pkg.applicationInfo;
            if (app == null || pkg.packageName.equals(context.getPackageName())) continue;
            String name;
            try { name = String.valueOf(app.loadLabel(pm)); } catch (RuntimeException unavailable) { name = pkg.packageName; }
            Intent launch = AppLaunch.launchIntent(pm, pkg.packageName);
            Intent channels = new Intent("yandex.intent.action.TV_CHANNEL").setPackage(pkg.packageName);
            if (launch == null && channels.resolveActivity(pm) == null) continue;
            choices.put(pkg.packageName, new String[]{pkg.packageName, name});
        }
        for (TvInputInfo input : inputs(context)) {
            if (input == null || input.getId() == null || input.isPassthroughInput()) continue;
            ServiceInfo service = input.getServiceInfo();
            if (service == null || choices.containsKey(service.packageName)) continue;
            String key = INPUT + input.getId();
            String label;
            try { label = String.valueOf(input.loadLabel(context)); } catch (RuntimeException unavailable) { label = input.getId(); }
            choices.put(key, new String[]{key, label});
        }
        List<String[]> result = new ArrayList<>(choices.values());
        result.sort((a,b) -> {
            int tierA = tvRank(a), tierB = tvRank(b);
            return tierA != tierB ? Integer.compare(tierA, tierB) : a[1].compareToIgnoreCase(b[1]);
        });
        return result;
    }
    private static int tvRank(String[] item) {
        String value = (item[0] + " " + item[1]).toLowerCase(java.util.Locale.ROOT);
        return item[0].startsWith(INPUT) || value.contains("iptv") || value.contains("live")
                || value.contains("channel") || value.contains("tv") || value.contains("телев") ? 0 : 1;
    }
    private static List<TvInputInfo> inputs(Context context) {
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager != null) {
                List<TvInputInfo> inputs = manager.getTvInputList();
                if (inputs != null) return inputs;
            }
        } catch (RuntimeException unavailable) { AppLog.w("TV inputs unavailable: " + unavailable.getClass().getSimpleName()); }
        return java.util.Collections.emptyList();
    }
    static String packageName(String key) {
        if (key.startsWith(ACTIVITY) || key.startsWith(SERVICE)) {
            ComponentName component = ComponentName.unflattenFromString(key.substring(key.indexOf(':') + 1));
            return component == null ? "" : component.getPackageName();
        }
        return key.startsWith(INPUT) ? "" : key;
    }
    @SuppressWarnings("deprecation") // Package component flags support Android 9.
    static Intent intent(Context context, String key) {
        PackageManager pm = context.getPackageManager();
        try {
            if (key.startsWith(ACTIVITY)) {
                ComponentName component = ComponentName.unflattenFromString(key.substring(ACTIVITY.length()));
                if (component == null) return null;
                ActivityInfo activity = pm.getActivityInfo(component, 0);
                if (!activity.exported || !activity.enabled || !allowed(context, activity.permission)) return null;
                return new Intent(Intent.ACTION_MAIN).setComponent(component);
            }
            String pkg = packageName(key);
            String serviceName = key.startsWith(SERVICE) ? key.substring(SERVICE.length()) : "";
            if (!pkg.isEmpty() && serviceName.isEmpty()) {
                Intent channels = new Intent("yandex.intent.action.TV_CHANNEL").setPackage(pkg);
                if (channels.resolveActivity(pm) != null) return channels;
            }
            for (TvInputInfo input : inputs(context)) {
                if (input == null || input.getId() == null) continue;
                ServiceInfo service = input.getServiceInfo();
                if (key.startsWith(INPUT) ? input.getId().equals(key.substring(INPUT.length()))
                        : service != null && (serviceName.isEmpty() ? !input.isPassthroughInput() && pkg.equals(service.packageName)
                        : new ComponentName(service.packageName,service.name).flattenToString().equals(serviceName))) {
                    Intent tune = new Intent(Intent.ACTION_VIEW, input.isPassthroughInput()
                            ? TvContract.buildChannelUriForPassthroughInput(input.getId()) : TvContract.buildChannelsUriForInput(input.getId()));
                    if (tune.resolveActivity(pm) != null) return tune;
                }
            }
            if (pkg.isEmpty()) return null;
            Intent launch = AppLaunch.launchIntent(pm, pkg);
            if (launch != null) return launch;
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (info.activities != null) for (ActivityInfo activity : info.activities) {
                if (activity != null && activity.name != null && activity.exported && activity.enabled && allowed(context, activity.permission))
                    return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, activity.name));
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            AppLog.w("TV target unavailable: " + unavailable.getClass().getSimpleName());
        }
        return null;
    }
    private static boolean allowed(Context context, String permission) {
        return permission == null || permission.isEmpty() || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
