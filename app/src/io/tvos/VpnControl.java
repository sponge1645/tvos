package io.tvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import java.io.IOException;

/** Explicit control for the supported client; no arbitrary shell commands or optimistic state. */
final class VpnControl {
    static final String PACKAGE = "llc.itdev.incy";
    private static final String RECEIVER = PACKAGE + ".receiver.VpnIntentReceiver";
    private static final String ACTION = PACKAGE + ".TOGGLE";
    private static final String COMMAND = "am broadcast -n llc.itdev.incy/.receiver.VpnIntentReceiver -a llc.itdev.incy.TOGGLE";
    private VpnControl() {}

    static void toggle(Context context) throws IOException {
        ComponentName component = new ComponentName(PACKAGE, RECEIVER);
        ActivityInfo receiver;
        try { receiver = context.getPackageManager().getReceiverInfo(component, 0); }
        catch (PackageManager.NameNotFoundException e) { throw new IOException("VPN receiver unavailable", e); }
        if (receiver.exported && (receiver.permission == null || receiver.permission.isEmpty()
                || context.checkSelfPermission(receiver.permission) == PackageManager.PERMISSION_GRANTED)) {
            try {
                context.sendBroadcast(new Intent(ACTION).setComponent(component));
                return;
            } catch (SecurityException e) { AppLog.w("VPN broadcast requires shell access"); }
        }
        try (Adb adb = Adb.connect(context, 15000, null)) {
            String result = adb.shell(COMMAND);
            if (!result.contains("Broadcast completed") || result.contains("Permission Denial")
                    || result.contains("SecurityException")) throw new IOException("VPN command rejected");
        }
    }
}
