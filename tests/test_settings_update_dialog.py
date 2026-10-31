"""Run the actual update modal with deterministic Android/updater adapters; no live OTA calls."""
from pathlib import Path
import subprocess, tempfile
from settings_strings_fixture import write
stubs={
'io/tvos/SettingsPopupBackdrop.java':'''package io.tvos;import io.tvos.settings.UpdateDialogText;import android.graphics.*;final class SettingsPopupBackdrop {interface Source{void draw(Canvas c);}boolean ready(){return false;}void clear(){}void prepare(int w,int h,boolean theme,Source source){}void draw(Canvas c,RectF panel,float radius){}}''',
'io/tvos/SettingsUpdateProgress.java':'package io.tvos;import io.tvos.settings.UpdateDialogText;class SettingsUpdateProgress {int kind,percent;void observe(int k,int p,long now){kind=k;percent=p;}}',
'android/content/Context.java':'''package android.content;public class Context {public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}public String getPackageName(){return "io.tvos";}}''',
'android/app/Activity.java':'''package android.app;public class Activity extends android.content.Context {}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public static int lookups;public boolean missing;public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}public static class PackageInfo {public String versionName="1.1";}public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{lookups++;if(missing)throw new NameNotFoundException();return new PackageInfo();}}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}''',
'android/view/View.java':'''package android.view;public class View {public int invalidations,delayed;public void invalidate(){invalidations++;}public void postInvalidateDelayed(long delay){if(delay!=16)throw new AssertionError("frame delay");delayed++;}}''',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,KEYCODE_BACK=4;final int action,code;public KeyEvent(int a,int c){action=a;code=c;}public int getAction(){return action;}public int getKeyCode(){return code;}}''',
'android/graphics/Shader.java':'''package android.graphics;public class Shader {public enum TileMode{CLAMP}}''',
'android/graphics/LinearGradient.java':'''package android.graphics;public class LinearGradient extends Shader {public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode mode){}}''',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1;public enum Align{CENTER,LEFT}public enum Style{FILL,STROKE}public int color,alpha=255;public float size;public static class FontMetrics{public float ascent,descent;}public void getFontMetrics(FontMetrics m){m.ascent=-size*.8f;m.descent=size*.2f;}public Paint(int flags){}public void setTextAlign(Align a){}public static int measurements;public float measureText(String s){measurements++;return s.length()*size*.48f;}public void reset(){alpha=255;}public void setAntiAlias(boolean b){}public void setColor(int c){color=c;}public void setAlpha(int a){alpha=a;}public void setStyle(Style s){}public void setStrokeWidth(float w){}public void setShader(Shader s){}public void clearShadowLayer(){}}''',
'android/graphics/RectF.java':'''package android.graphics;public class RectF {public float left,top,right,bottom;public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public float centerX(){return (left+right)/2;}public float centerY(){return (top+bottom)/2;}}''',
'android/graphics/Canvas.java':'''package android.graphics;import java.util.*;public class Canvas {public final List<String> text=new ArrayList<>();public final List<Integer> colors=new ArrayList<>();public final List<Float> sizes=new ArrayList<>(),ys=new ArrayList<>();public int depth,layerAlpha=-1,dimAlpha;public float scale,panelLeft,panelTop,panelBottom,buttonRadius;public void save(){depth++;}public void restore(){if(--depth<0)throw new AssertionError("unbalanced canvas");}public void scale(float a,float b,float x,float y){scale=a;}public void saveLayerAlpha(float l,float t,float r,float b,int a){depth++;layerAlpha=a;}public void drawRect(float l,float t,float r,float b,Paint p){dimAlpha=p.alpha;colors.add(p.color);}public void drawRoundRect(RectF r,float x,float y,Paint p){if(text.isEmpty()){panelLeft=r.left;panelTop=r.top;panelBottom=r.bottom;}else buttonRadius=x;}public void drawText(String s,float x,float y,Paint p){text.add(s);colors.add(p.color);sizes.add(p.size);ys.add(y);}}''',
'io/tvos/Ui.java':'''package io.tvos;import io.tvos.settings.UpdateDialogText;import android.graphics.Paint;final class Ui {static final Object displaySemibold=new Object(),displayBold=new Object(),displayMedium=new Object(),regular=new Object();static Paint text(Object face,float size,int color){Paint p=new Paint(1);p.size=size;p.setColor(color);return p;}static float halfCap(Paint p){return p.size/3;}}''',
'io/tvos/SelfUpdate.java':'''package io.tvos;import io.tvos.settings.UpdateDialogText;import java.util.*;import android.app.Activity;import android.content.Context;final class SelfUpdate {
 static final int IDLE=0,CHECKING=1,CURRENT=2,AVAILABLE=3,DOWNLOADING=4,READY=5,ERROR=6;interface Listener{void onUpdate();}
 static SelfUpdate instance=new SelfUpdate();static SelfUpdate get(Context c){return instance;}
 static class Release {final String versionName="1.2.3";}static class Status {final int kind,percent;final String message;final Release release;Status(int k,int p,String m){kind=k;percent=p;message=m;release=new Release();}}
 Status current=new Status(IDLE,-1,"");final List<Listener> listeners=new ArrayList<>();int checks,downloads,installs,automatic;
 Status status(){return current;}void addListener(Listener l){if(!listeners.contains(l))listeners.add(l);}void removeListener(Listener l){listeners.remove(l);}
 void emit(int k,int p,String m){current=new Status(k,p,m);for(Listener l:new ArrayList<>(listeners))l.onUpdate();}
 void maybeAutomatic(boolean force){automatic++;}void check(boolean automatic){checks++;emit(CHECKING,-1,"");}void download(){downloads++;emit(DOWNLOADING,-1,"");}void install(Activity a){installs++;}
}'''
}
harness='''package io.tvos;import io.tvos.settings.UpdateDialogText;
import android.app.Activity;import android.view.*;import android.graphics.*;import android.os.SystemClock;
public class UpdateDialogHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static final int OK=KeyEvent.KEYCODE_DPAD_CENTER,BACK=KeyEvent.KEYCODE_BACK,DOWN=KeyEvent.KEYCODE_DPAD_DOWN,UP=KeyEvent.KEYCODE_DPAD_UP;
 static void key(SettingsUpdateDialog d,int code){check(d.onKey(new KeyEvent(KeyEvent.ACTION_UP,code)),"modal consumes input");}
 static Canvas draw(SettingsUpdateDialog d,boolean light){Canvas c=new Canvas();d.draw(c,1920,1080,light,false,false);check(c.depth==0,"balanced canvas state");return c;}
 static SettingsUpdateProgress progress;
 static SettingsUpdateDialog create(){SelfUpdate.instance=new SelfUpdate();SettingsUpdateDialog d=new SettingsUpdateDialog(new Activity(),new View(),progress=new SettingsUpdateProgress(),null);d.resize(1);d.resume();return d;}
 public static void main(String[] args){
  AppLanguage.select("en");SettingsUpdateDialog d=create();SelfUpdate u=SelfUpdate.instance;
  check(!d.visible()&&u.listeners.size()==1&&u.automatic==1,"resume registers once without opening unsolicited modal");
  u.emit(SelfUpdate.DOWNLOADING,12,"");check(!d.visible()&&progress.percent==12,"background download reaches progress without unsolicited modal");
  d.check();check(!d.visible()&&u.checks==1&&progress.kind==SelfUpdate.CHECKING,"checking stays inline with immediate status");
  Canvas c;
  u.emit(SelfUpdate.AVAILABLE,-1,"");SystemClock.now+=260;c=draw(d,false);
  check(String.join(" ",c.text).contains("Update 1.2.3 is available.")&&c.text.contains("Download and Install")&&c.text.contains("Update Later"),"available copy and two buttons");
  check(c.panelLeft==710f&&c.panelTop>90f&&c.panelBottom-c.panelTop<900&&c.buttonRadius==36&&c.dimAlpha==175,"panel geometry and dark dimming retained");
  d.onKey(new KeyEvent(KeyEvent.ACTION_DOWN,OK));check(u.downloads==0,"download only on key release");
  key(d,DOWN);key(d,UP);key(d,OK);check(u.downloads==1,"focused primary downloads once");
  u.emit(SelfUpdate.DOWNLOADING,47,"");SystemClock.now+=260;c=draw(d,true);check(!d.visible()&&progress.kind==SelfUpdate.DOWNLOADING&&progress.percent==47,"download fades to inline page progress");
  u.emit(SelfUpdate.READY,100,"");check(u.installs==1,"download and install intent continues when ready");
  u.emit(SelfUpdate.READY,100,"");check(u.installs==1,"duplicate ready callback does not reinstall");
  check(!d.visible(),"ready goes directly to native installer without another tvOS popup");
  int notices=u.installs;u.emit(SelfUpdate.AVAILABLE,-1,"");SystemClock.now+=260;draw(d,true);check(!d.visible()&&u.installs==notices,"late status cannot reopen dismissed dialog");
  d=create();u=SelfUpdate.instance;d.check();u.emit(SelfUpdate.AVAILABLE,-1,"");key(d,OK);key(d,BACK);u.emit(SelfUpdate.READY,100,"");check(u.installs==0,"Back cancels automatic install intent");
  SystemClock.now+=260;draw(d,true);check(!d.visible(),"Back finishes closing");
  d=create();u=SelfUpdate.instance;d.check();u.emit(SelfUpdate.AVAILABLE,-1,"");key(d,OK);d.pause();check(u.listeners.isEmpty(),"pause releases listener");
  u.emit(SelfUpdate.READY,100,"");check(u.installs==0,"paused modal does not launch installer");d.resume();check(u.installs==1&&u.listeners.size()==1,"resume reconciles pending download once");
  d.pause();d.pause();check(u.listeners.isEmpty(),"repeated lifecycle pause safe");
  d=create();u=SelfUpdate.instance;d.check();u.emit(SelfUpdate.READY,100,"");key(d,OK);check(u.installs==1,"ready primary installs immediately");
  for(String language:new String[]{"en","ru"}){
   AppLanguage.select(language);boolean ru=language.equals("ru");
   u.emit(SelfUpdate.CURRENT,-1,"");SystemClock.now+=260;c=draw(d,true);check(String.join(" ",c.text).contains(ru?"На ТВ установлена актуальная версия ПО: tvOS 1.1":"Your TV has the latest software: tvOS 1.1")&&c.text.contains("1.1 (1B121)"),"current version localized");check(c.text.get(0).equals(AppStrings.get(R.string.settings_tvos_update))&&c.text.get(1).equals("1.1 (1B121)")&&c.ys.get(2)-c.ys.get(1)==33&&c.panelBottom-c.panelTop<550,"current layout keeps title, adjacent version/body lines, compact height and capsule button");
   u.emit(SelfUpdate.ERROR,-1,"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");SystemClock.now+=260;c=draw(d,false);check(String.join("",c.text).contains("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"),"wrapped error retains all characters");
   u.emit(SelfUpdate.AVAILABLE,-1,"");SystemClock.now+=260;c=draw(d,true);check(c.text.contains(ru?"Загрузить и установить":"Download and Install"),"language read at drawing time");
   u.emit(SelfUpdate.READY,100,"");SystemClock.now+=260;c=draw(d,true);check(c.text.contains(ru?"Версия 1.2.3":"Version 1.2.3"),"ready version spacing");
  }
  d.resize(.5f);c=draw(d,true);check(c.panelLeft==355f&&c.panelTop>45f&&c.sizes.get(0)==16.5f,"resize updates geometry and fonts");
  d=create();u=SelfUpdate.instance;d.check();u.emit(SelfUpdate.ERROR,-1,"failure");SystemClock.now+=260;draw(d,false);
  key(d,BACK);SystemClock.now+=200;key(d,BACK);SystemClock.now+=60;draw(d,false);check(!d.visible(),"repeated Back cannot restart close deadline");
  d.check();u.emit(SelfUpdate.CURRENT,-1,"");Canvas reduced=new Canvas();d.draw(reduced,1920,1080,false,false,true);check(reduced.scale==1&&reduced.layerAlpha==-1,"reduced motion opens without scale or fade");key(d,OK);d.draw(new Canvas(),1920,1080,false,false,true);check(!d.visible(),"reduced motion closes immediately");
  d.check();u.emit(SelfUpdate.CURRENT,-1,"");SystemClock.now+=260;draw(d,false);int reads=android.content.pm.PackageManager.lookups,measures=Paint.measurements;for(int frame=0;frame<60;frame++){SystemClock.now+=16;draw(d,false);}check(android.content.pm.PackageManager.lookups==reads&&Paint.measurements==measures,"steady dialog reuses installed version and text wrapping");
  d.check();u.emit(SelfUpdate.ERROR,-1,String.join("",java.util.Collections.nCopies(2000,"😃")));SystemClock.now+=260;c=draw(d,false);check(c.panelTop>=39&&c.panelBottom<=1041,"long Unicode error stays on screen");for(int i=1;i<c.text.size()-1;i++){check(c.text.get(i).length()*25*.48f<=410.01f,"unbroken error stays inside body column");}
  Activity a=new Activity();check(SettingsUpdateDialog.versionName(a).equals("tvOS 1.1 (1B121)"),"installed version lookup");a.pm.missing=true;check(SettingsUpdateDialog.versionName(a).equals("tvOS"),"missing version safe fallback");
  System.out.println("PASS: actual update modal lifecycle, input, status transitions, deferred install, cancellation, fades, geometry and live localization");
 }
}
'''
stubs['io/tvos/version/SoftwareBuild.java']=Path('app/src/io/tvos/version/SoftwareBuild.java').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-update-dialog-') as tmp:
 root=Path(tmp);write(root)
 stubs['io/tvos/UpdateDialogText.java']=Path('app/src/io/tvos/settings/UpdateDialogText.java').read_text()
 stubs['io/tvos/SettingsUpdateDialog.java']=Path('app/src/io/tvos/SettingsUpdateDialog.java').read_text()
 stubs['io/tvos/UpdateDialogHarness.java']=harness
 for name,content in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.UpdateDialogHarness'],check=True)
