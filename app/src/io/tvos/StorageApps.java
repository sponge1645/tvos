package io.tvos;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import java.util.ArrayList;
import java.util.List;

/** Storage work is run off the UI thread. Unknown sizes stay unknown, never masquerade as APK totals. */
final class StorageApps {
    static final class Item {
        final String pkg,label;
        final boolean removable;
        final long bytes;
        Item(String pkg,String label,boolean removable,long bytes){this.pkg=pkg;this.label=label;this.removable=removable;this.bytes=bytes;}
    }
    private StorageApps() { }
    @SuppressWarnings("deprecation") // API 28 compatibility.
    private static boolean usageAllowed(Context c) {
        try {
            AppOpsManager ops=c.getSystemService(AppOpsManager.class);
            return ops!=null&&ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
        }catch(RuntimeException unavailable){return false;}
    }
    static List<Item> load(Context c) {
        if(!usageAllowed(c))try(Adb adb=Adb.connect(c,1500,()->{})){adb.shell("appops set --user "+(Process.myUid()/100000)+" io.tvos GET_USAGE_STATS allow");}
        catch(Exception e){AppLog.w("Storage usage access unavailable: "+e.getClass().getSimpleName());}
        PackageManager pm=c.getPackageManager();StorageStatsManager stats=c.getSystemService(StorageStatsManager.class);
        List<Item> items=new ArrayList<>();
        for(ApplicationInfo app:pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if(Thread.currentThread().isInterrupted())break;
            if((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)continue;
            long bytes=-1;
            try {
                StorageStats size=stats.queryStatsForPackage(app.storageUuid==null?StorageManager.UUID_DEFAULT:app.storageUuid,
                        app.packageName,UserHandle.getUserHandleForUid(app.uid));
                // dataBytes already includes cached data; adding cacheBytes again would double-count it.
                bytes=Math.addExact(Math.max(0,size.getAppBytes()),Math.max(0,size.getDataBytes()));
            }catch(Exception ignored){}
            items.add(new Item(app.packageName,String.valueOf(app.loadLabel(pm)),!c.getPackageName().equals(app.packageName),bytes));
        }
        items.sort((a,b)->{int bySize=Long.compare(b.bytes,a.bytes);return bySize!=0?bySize:a.label.compareToIgnoreCase(b.label);});
        return items;
    }
    @SuppressWarnings("deprecation") // Standard platform uninstall UI on supported Android TVs.
    static android.content.Intent uninstallIntent(Context c,String pkg) {
        if(pkg==null||!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")||pkg.equals(c.getPackageName()))return null;
        try {
            ApplicationInfo app=c.getPackageManager().getApplicationInfo(pkg,0);
            if((app.flags&ApplicationInfo.FLAG_SYSTEM)!=0)return null;
            return new android.content.Intent(android.content.Intent.ACTION_UNINSTALL_PACKAGE,
                    android.net.Uri.fromParts("package",pkg,null)).putExtra(android.content.Intent.EXTRA_RETURN_RESULT,true);
        }catch(Exception e){return null;}
    }
}
