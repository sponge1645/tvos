"""Exercise the real VPN command adapter, direct delivery and privileged fallback."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/ComponentName.java':'''package android.content;public class ComponentName {public String pkg,name;public ComponentName(String p,String n){pkg=p;name=n;}}''',
'android/content/Intent.java':'''package android.content;public class Intent {public String action;public ComponentName component;public Intent(String a){action=a;}public Intent setComponent(ComponentName c){component=c;return this;}}''',
'android/content/pm/ActivityInfo.java':'''package android.content.pm;public class ActivityInfo {public boolean exported=true;public String permission;}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;public boolean missing;public ActivityInfo info=new ActivityInfo();public static class NameNotFoundException extends Exception {private static final long serialVersionUID=1L;}public ActivityInfo getReceiverInfo(android.content.ComponentName c,int flags)throws NameNotFoundException {if(missing)throw new NameNotFoundException();return info;}}''',
'android/content/Context.java':'''package android.content;public class Context {public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public boolean denied;public int permission=-1,broadcasts;public Intent sent;public android.content.pm.PackageManager getPackageManager(){return pm;}public int checkSelfPermission(String p){return permission;}public void sendBroadcast(Intent i){if(denied)throw new SecurityException();broadcasts++;sent=i;}}''',
'io/tvos/AppLog.java':'''package io.tvos;class AppLog {static void w(String s){}}''',
'io/tvos/Adb.java':'''package io.tvos;import java.io.*;class Adb implements AutoCloseable {static int connects,closed;static String result="Broadcast completed: result=0",command;static Adb connect(android.content.Context c,int wait,Object callback){connects++;return new Adb();}String shell(String c){command=c;return result;}public void close(){closed++;}}''',
'io/tvos/VpnControl.java':Path('app/src/io/tvos/VpnControl.java').read_text(),
'io/tvos/VpnHarness.java':'''package io.tvos;import android.content.*;public class VpnHarness {static void check(boolean v){if(!v)throw new AssertionError();}public static void main(String[] args)throws Exception {
 Context c=new Context();VpnControl.toggle(c);check(c.broadcasts==1&&Adb.connects==0);check(c.sent.action.equals("llc.itdev.incy.TOGGLE")&&c.sent.component.pkg.equals(VpnControl.PACKAGE)&&c.sent.component.name.endsWith(".receiver.VpnIntentReceiver"));
 c.pm.info.permission="signature.permission";VpnControl.toggle(c);check(c.broadcasts==1&&Adb.connects==1&&Adb.closed==1);check(Adb.command.equals("am broadcast -n llc.itdev.incy/.receiver.VpnIntentReceiver -a llc.itdev.incy.TOGGLE"));
 c.permission=0;VpnControl.toggle(c);check(c.broadcasts==2&&Adb.connects==1);
 c.denied=true;VpnControl.toggle(c);check(Adb.connects==2&&Adb.closed==2);
 c.denied=false;c.pm.info.exported=false;Adb.result="SecurityException: Permission Denial";try{VpnControl.toggle(c);throw new AssertionError();}catch(java.io.IOException expected){}check(Adb.closed==3);
 c.pm.missing=true;try{VpnControl.toggle(c);throw new AssertionError();}catch(java.io.IOException expected){}check(Adb.connects==3);
 System.out.println("PASS: explicit VPN target, direct control, permission/export checks, fixed ADB fallback and rejected commands");
}}'''}
home=Path('app/src/io/tvos/HomeView.java').read_text()
def method(signature):
 start=home.index(signature);body=home.index('{',start);depth=1;end=body+1
 while depth:
  depth+=(home[end]=='{')-(home[end]=='}');end+=1
 return home[start:end]
sources['io/tvos/Spring.java']=Path('app/src/io/tvos/Spring.java').read_text()
sources['io/tvos/ButtonHarness.java']='package io.tvos;class ButtonHarness {\n boolean running=true,sheetOpen,drawerOpen,searchMode,okDown=true,vpnPress=true;int zone=1,topIndex=7,opens;static final int Z_TOP=1;boolean topButtonEnabled(int i){return true;}void openVpnApp(){opens++;}\n'+method('    private boolean vpnFocused(')+method('    private void openVpnOnHold(')+'\n static void check(boolean v){if(!v)throw new AssertionError();}public static void main(String[] args){\n ButtonHarness h=new ButtonHarness();h.topIndex=6;h.openVpnOnHold();check(h.opens==0&&h.okDown);h.topIndex=7;h.openVpnOnHold();check(h.opens==1&&!h.okDown);h.openVpnOnHold();check(h.opens==1);\n h.okDown=true;h.running=false;h.openVpnOnHold();check(h.opens==1);h.running=true;h.vpnPress=false;h.openVpnOnHold();check(h.opens==1);\n Spring tint=new Spring(.35f,1,0);tint.target=1;float prior=0;for(int i=0;i<100;i++){tint.step(.016f);check(tint.value>=prior&&tint.value<=1.001f);prior=tint.value;}check(tint.value>.999f);tint.target=0;for(int i=0;i<100;i++){tint.step(.016f);check(tint.value<=prior&&tint.value>=-.001f);prior=tint.value;}check(tint.value<.001f);\n System.out.println("PASS: real hold eligibility, consumed long press, no repeat opening and reversible smooth VPN tint");\n }}'
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,content in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.VpnHarness'],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ButtonHarness'],check=True)
