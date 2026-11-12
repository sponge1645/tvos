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
    static final int UNINSTALL_REQUEST=905,INSTALL_REQUEST=906;
    private final Activity activity;private final Handler main=new Handler(Looper.getMainLooper());
    private AtomicBoolean cancelled=new AtomicBoolean();private AlertDialog dialog;private boolean closed;
    private boolean installing,recoveryVisible;
    private final Runnable focusRecovery=new Runnable(){@Override public void run(){
        if(!closed&&activity.hasWindowFocus()){
            if(pending!=null){
                if(ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName)==null)uninstallReturned();
                return;
            }
            installing=false;resumeRecovery();
        }
    }};
    void windowFocus(boolean focused){main.removeCallbacks(focusRecovery);if(focused)main.postDelayed(focusRecovery,500);}
    private long heldDownTime=-1;private TizenReleases.Version pending;
    TizenVersionPicker(Activity activity){this.activity=activity;}
    private String text(int id){return AppStrings.get(id);}
    static boolean recoveryPending(Activity activity){return TizenRecovery.read(activity.getFilesDir())!=null;}
    void show(long downTime){
        heldDownTime=downTime;if(resumeRecovery())return;
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
        if(installed==null){launchPrepared(version);return;}
        dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions)).setMessage(text(R.string.tizen_downgrade))
                .setPositiveButton(text(R.string.storage_delete),(d,w)->uninstall(version))
                .setNegativeButton(text(R.string.settings_cancel),null).show();
    }
    @SuppressWarnings("deprecation")
    private void uninstall(TizenReleases.Version version){
        if(!AppDownloads.hasPreparedTizen(activity,version.source)){message(text(R.string.tizen_versions_failed));return;}
        if(!persist(version))return;pending=version;
        try{activity.startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.fromParts("package",AppDownloadCatalog.get(2).packageName,null))
                .putExtra(Intent.EXTRA_RETURN_RESULT,true),UNINSTALL_REQUEST);}
        catch(RuntimeException e){pending=null;message(text(R.string.storage_delete_failed));}
    }
    void uninstallReturned(){
        TizenReleases.Version version=pending;pending=null;if(closed)return;
        if(ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName)!=null){TizenRecovery.clear(activity.getFilesDir());return;}
        if(version==null)version=recovered();if(version!=null)launchPrepared(version);
    }
    private TizenReleases.Version recovered(){
        TizenRecovery record=TizenRecovery.read(activity.getFilesDir());
        return record==null?null:new TizenReleases.Version(record.version,new AppDownloadCatalog.Source(record.url,record.sha256));
    }
    private boolean persist(TizenReleases.Version version){
        TizenRecovery current=TizenRecovery.read(activity.getFilesDir());
        if(current!=null&&current.sha256.equals(version.source.sha256))return true;
        try{new TizenRecovery(version.name,version.source.url,version.source.sha256).write(activity.getFilesDir());return true;}
        catch(java.io.IOException e){message(text(R.string.tizen_recovery_save_failed));return false;}
    }
    private void launchPrepared(TizenReleases.Version version){
        if(!persist(version))return;
        installing=true;
        if(!AppDownloads.installPreparedTizen(activity,version.source)){installing=false;resumeRecovery();}
    }
    void installationReturned(){installing=false;resumeRecovery();}
    boolean resumeRecovery(){
        if(closed||installing||pending!=null||!activity.hasWindowFocus())return false;
        TizenReleases.Version version=recovered();if(version==null)return false;
        if(ApkTrust.installed(activity.getPackageManager(),AppDownloadCatalog.get(2).packageName)!=null){
            if(recoveryVisible&&dialog!=null){recoveryVisible=false;dialog.dismiss();}
            TizenRecovery.clear(activity.getFilesDir());return false;
        }
        if(recoveryVisible)return true;
        boolean cached=AppDownloads.hasPreparedTizen(activity,version.source);recoveryVisible=true;
        dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions))
                .setMessage(text(cached?R.string.tizen_recovery_ready:R.string.tizen_recovery_download))
                .setPositiveButton(text(cached?R.string.tizen_recovery_retry:R.string.tizen_download),(d,w)->{
                    recoveryVisible=false;
                    if(cached)launchPrepared(version);
                    else{java.lang.ref.WeakReference<TizenVersionPicker> owner=new java.lang.ref.WeakReference<>(this);
                        AppDownloads.prepareTizenVersion(activity,version.source,()->{TizenVersionPicker current=owner.get();if(current!=null&&!current.closed)current.launchPrepared(version);});}
                }).setNegativeButton(text(R.string.tizen_recovery_later),(d,w)->recoveryVisible=false)
                .setNeutralButton(text(R.string.tizen_recovery_choose),(d,w)->{recoveryVisible=false;TizenRecovery.clear(activity.getFilesDir());show(-1);}).create();
        dialog.setOnCancelListener(d->recoveryVisible=false);guardRelease(dialog);dialog.show();return true;
    }
    private void message(String message){dialog=new AlertDialog.Builder(activity).setTitle(text(R.string.tizen_versions)).setMessage(message).setPositiveButton("OK",null).show();}
    @Override public void close(){closed=true;main.removeCallbacks(focusRecovery);cancelled.set(true);pending=null;if(dialog!=null)dialog.dismiss();}
}
