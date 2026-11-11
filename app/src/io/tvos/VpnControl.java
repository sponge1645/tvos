package io.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import java.io.IOException;

/** Explicit control for the supported clients; no arbitrary shell commands or optimistic state. */
final class VpnControl {
    static final String PACKAGE = "llc.itdev.incy";
    static final String HAPP_PACKAGE = "su.happ.proxyutility";
    private VpnControl() {}

    static void toggle(Context context) throws IOException {
        toggle(context, PACKAGE);
    }

    static boolean supports(String pkg) {
        return PACKAGE.equals(pkg) || HAPP_PACKAGE.equals(pkg);
    }

    static void toggle(Context context, String pkg) throws IOException {
        if (!supports(pkg)) throw new IOException("Unsupported VPN client");
        boolean happ = HAPP_PACKAGE.equals(pkg);
        String receiverName = pkg + (happ ? ".receiver.WidgetProvider" : ".receiver.VpnIntentReceiver");
        String action = pkg + (happ ? ".action.widget.click" : ".TOGGLE");
        ComponentName component = new ComponentName(pkg, receiverName);
        ActivityInfo receiver;
        try { receiver = context.getPackageManager().getReceiverInfo(component, 0); }
        catch (PackageManager.NameNotFoundException e) { throw new IOException("VPN receiver unavailable", e); }
        if (receiver.exported && (receiver.permission == null || receiver.permission.isEmpty()
                || context.checkSelfPermission(receiver.permission) == PackageManager.PERMISSION_GRANTED)) {
            try {
                context.sendBroadcast(new Intent(action).setComponent(component));
                return;
            } catch (SecurityException e) { AppLog.w("VPN broadcast requires shell access"); }
        }
        try (Adb adb = Adb.connect(context, 15000, null)) {
            String result = adb.shell(happ
                    ? "am broadcast -n su.happ.proxyutility/.receiver.WidgetProvider -a su.happ.proxyutility.action.widget.click"
                    : "am broadcast -n llc.itdev.incy/.receiver.VpnIntentReceiver -a llc.itdev.incy.TOGGLE");
            if (!result.contains("Broadcast completed") || result.contains("Permission Denial")
                    || result.contains("SecurityException")) throw new IOException("VPN command rejected");
        }
    }
}
