"""Run the actual in-window remote host open/close route without Android windows."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
harness='''class View {static int VISIBLE=0,INVISIBLE=4;int visibility=0,focus,invalidations;ViewGroup parent;Object getParent(){return parent;}void setVisibility(int v){visibility=v;}void requestFocus(){focus++;}void invalidate(){invalidations++;}}
class ViewGroup {static class LayoutParams{LayoutParams(int w,int h){}}int adds,removes;void addView(View v,LayoutParams p){adds++;}void removeView(View v){removes++;}}
class RemoteShortcutSettings {View content=new View();Runnable exit;int closes,starts;RemoteShortcutSettings(Object a,View source,String title,Runnable exit){this.exit=exit;}View content(){return content;}void close(){closes++;}void start(boolean options){if(options)throw new AssertionError();starts++;}}
public class Harness {
 RemoteShortcutSettings remoteSettings;static class Shortcuts {int reloads;void reload(){reloads++;}}Shortcuts shortcuts=new Shortcuts();
'''+block(source,'    void openRemoteSettings(').replace('android.view.ViewGroup','ViewGroup')+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){Harness h=new Harness();View source=new View();source.parent=new ViewGroup();
 h.openRemoteSettings(source,"Settings");RemoteShortcutSettings menu=h.remoteSettings;
 check(menu.starts==1&&source.visibility==4&&source.parent.adds==1);h.openRemoteSettings(source,"Settings");check(h.remoteSettings==menu&&source.parent.adds==1);
 menu.exit.run();check(h.remoteSettings==null&&menu.closes==1&&source.parent.removes==1&&source.visibility==0&&source.focus==1&&source.invalidations==1&&h.shortcuts.reloads==1);
 System.out.println("PASS: one in-window menu, duplicate-open rejection, close cleanup and exact source focus restoration");
 }}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'Harness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(root/'Harness.java')],check=True)
 subprocess.run(['java','-cp',d,'Harness'],check=True)
