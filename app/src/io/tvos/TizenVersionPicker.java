package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import io.tvos.version.AppVersions;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit release choice; an older release uses the native uninstall consent before downloading. */
final class TizenVersionPicker implements AutoCloseable {
    static final int UNINSTALL_REQUEST=905;
    private final Activity activity;private final Handler main=new Handler(Looper.getMainLooper());
    private AtomicBoolean cancelled=new AtomicBoolean();private AlertDialog dialog;private boolean closed;
    private long heldDownTime=-1;private TizenReleases.Version pending;
    TizenVersionPicker(Activity activity){this.activity=activity;}
    private String text(int id){return AppStrings.get(id);}
    void show(long downTime){
        cancelled.set(true);if(dialog!=null)dialog.dismiss();cancelled=new AtomicBoolean();AtomicBoolean token=cancelled;heldDownTime=downTime;
        dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions)).setMessage(text(R.string.remote_loading))
                .setNegativeButton(text(R.string.settings_cancel),(d,w)->token.set(true)).create();
        dialog.setOnCancelListener(d->token.set(true));guardRelease(dialog);dialog.show();
        new Thread(()->{
            try{List<TizenReleases.Version> versions=TizenReleases.load(Build.SUPPORTED_ABIS,token::get);
                main.post(()->{if(active(token)){dialog.dismiss();if(versions.isEmpty())message(text(R.string.runtime_no_compatible_apk_in_releases));else choose(versions);}});
            }catch(Exception e){main.post(()->{if(active(token)){dialog.dismiss();message(text(R.string.tizen_versions_failed));}});}
        },"tvos-tizen-releases").start();
    }
    private boolean active(AtomicBoolean token){return !closed&&token==cancelled&&!token.get()&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void guardRelease(AlertDialog target){target.setOnKeyListener((d,key,event)->{
        if(key!=23&&key!=66&&key!=160)return false;
        if(heldDownTime>=0&&event.getDownTime()==heldDownTime){if(event.getAction()==KeyEvent.ACTION_UP)heldDownTime=-1;return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN)heldDownTime=-1;return false;
    });}
    private void choose(List<TizenReleases.Version> versions){
        PackageInfo installed=ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName);
        String[] names=new String[versions.size()];int selected=0;
        for(int n=0;n<names.length;n++){
            names[n]=versions.get(n).name+(n==0?" ("+text(R.string.tizen_latest)+")":"");
            Integer same=installed==null?null:AppVersions.compare(versions.get(n).name,installed.versionName);if(same!=null&&same==0)selected=n;
        }
        final int[] choice={selected};
        dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions))
                .setSingleChoiceItems(names,selected,(d,which)->choice[0]=which)
                .setPositiveButton(text(R.string.tizen_download),(d,w)->select(versions.get(choice[0])))
                .setNegativeButton(text(R.string.settings_cancel),null).create();guardRelease(dialog);dialog.show();
    }
    private void select(TizenReleases.Version version){
        if(AppDownloads.busy()){message(text(R.string.runtime_another_download_is_still_running));return;}
        PackageInfo installed=ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName);
        Integer order=installed==null?null:AppVersions.compare(version.name,installed.versionName);
        if(order!=null&&order<0){
            java.lang.ref.WeakReference<TizenVersionPicker> owner=new java.lang.ref.WeakReference<>(this);
            AppDownloads.prepareTizenVersion(activity,version.source,()->{TizenVersionPicker current=owner.get();if(current!=null)current.prepared(version);});return;
        }
        AppDownloads.startTizenVersion(activity,version.source);
    }
    private void prepared(TizenReleases.Version version){
        if(closed)return;
        PackageInfo installed=ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName);
        if(installed==null){AppDownloads.installPreparedTizen(activity,version.source);return;}
        dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions)).setMessage(text(R.string.tizen_downgrade))
                .setPositiveButton(text(R.string.storage_delete),(d,w)->uninstall(version))
                .setNegativeButton(text(R.string.settings_cancel),null).show();
    }
    @SuppressWarnings("deprecation")
    private void uninstall(TizenReleases.Version version){
        if(!AppDownloads.hasPreparedTizen(activity,version.source)){message(text(R.string.tizen_versions_failed));return;}
        pending=version;
        try{activity.startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.fromParts("package",AppDownloadCatalog.get(2).packageName,null))
                .putExtra(Intent.EXTRA_RETURN_RESULT,true),UNINSTALL_REQUEST);}
        catch(RuntimeException e){pending=null;message(text(R.string.storage_delete_failed));}
    }
    void uninstallReturned(){
        TizenReleases.Version version=pending;pending=null;if(closed||version==null)return;
        if(ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName)==null)AppDownloads.installPreparedTizen(activity,version.source);
    }
    private void message(String message){dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions)).setMessage(message).setPositiveButton("OK",null).show();}
    @Override public void close(){closed=true;cancelled.set(true);pending=null;if(dialog!=null)dialog.dismiss();}
}
