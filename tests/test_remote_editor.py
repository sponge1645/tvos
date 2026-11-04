"""Exercise production capture/edit/scope/delete behavior without platform drawing."""
from pathlib import Path
import re,subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/RemoteShortcutSettingsActivity.java').read_text()
methods='\n'.join(block(source,sig) for sig in ['    private void capture(', '    private boolean learn(', '    private void edit(', '    private void scope(', '    private void assign(', '    private void delete('])
ids=sorted(set(re.findall(r'R\.string\.(\w+)',methods)))
r='static class R {static class string {'+''.join('static final int '+name+'='+str(i+1)+';' for i,name in enumerate(ids))+'}}'
harness='''package io.tvos;import java.util.*;import io.tvos.remote.RemoteBindings;import io.tvos.remote.RemoteBindings.Binding;
class InputDevice {static final int SOURCE_GAMEPAD=0x401,SOURCE_JOYSTICK=0x1000010;}
class KeyEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,KEYCODE_VOLUME_UP=24,KEYCODE_VOLUME_DOWN=25,KEYCODE_VOLUME_MUTE=164;
 int action,code,scan,device=1,repeat,source;boolean cancelled;String name="Remote";
 KeyEvent(int a,int c,int s){action=a;code=c;scan=s;}KeyEvent(KeyEvent e){action=e.action;code=e.code;scan=e.scan;device=e.device;repeat=e.repeat;name=e.name;}
 int getAction(){return action;}int getKeyCode(){return code;}int getScanCode(){return scan;}int getDeviceId(){return device;}int getRepeatCount(){return repeat;}int getSource(){return source;}boolean isCanceled(){return cancelled;}
}
class RemoteShortcuts {static String device(KeyEvent e){return e.name;}}
public class Test {
'''+r+'''
 static class Row {String label,value;boolean checked;Runnable action;Row(String l,String v,boolean c,Runnable a){label=l;value=v;checked=c;action=a;}}
 Row row(int id,Runnable action){return new Row(text(id),"",false,action);}Row row(String l,String v,Runnable action){return new Row(l,v,false,action);}
 static class View {void announceForAccessibility(String s){}}final View view=new View();
 final List<Binding> bindings=new ArrayList<>();List<Row> rows;Runnable previous;String title,hint;int selected,saves,roots,messages,editedAction;
 boolean capturing,captureBackDown,busy;KeyEvent learned;
 String text(int id){return "text"+id;}String name(Binding b){return "Button "+b.code;}String label(String a){return a;}
 void show(String t,String h,List<Row> r,Runnable p,int at,int depth){title=t;hint=h;rows=r;previous=p;selected=at;capturing=false;learned=null;captureBackDown=false;}
 void root(){roots++;capturing=false;}void goBack(){if(previous!=null)previous.run();}void message(int id){messages++;}
 void save(Runnable complete){saves++;complete.run();}void actions(int at,int tap){editedAction=tap;}
'''+methods+'''
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void tap(Test t,int code,int scan){t.learn(new KeyEvent(0,code,scan));t.learn(new KeyEvent(1,code,scan));}
 public static void main(String[] args){
  Test t=new Test();t.capture();t.learn(new KeyEvent(1,185,400));check(t.bindings.isEmpty(),"orphan release cannot learn");
  t.learn(new KeyEvent(0,185,400));check(t.bindings.isEmpty(),"capture waits for release");t.learn(new KeyEvent(1,185,400));
  check(t.bindings.size()==1&&t.bindings.get(0).global&&t.saves==1&&!t.capturing,"new app button works everywhere by default");
  check(t.rows.size()==5&&t.selected==1,"simple edit has scope, three gestures and delete");
  t.assign(0,2,"vpn");check(t.bindings.get(0).actions()[2].equals("vpn"),"triple assignment retained");
  int saves=t.saves;t.capture();tap(t,185,400);check(t.bindings.size()==1&&t.saves==saves&&t.bindings.get(0).actions()[2].equals("vpn"),"learning an existing key edits without overwriting");
  t.scope(0);check(t.rows.size()==2&&t.rows.get(1).checked,"scope shows current Everywhere choice");t.rows.get(0).action.run();check(!t.bindings.get(0).global,"explicit Home-only selection preserved");
  t.capture();tap(t,23,7);Binding ok=t.bindings.get(1);check(!ok.global&&ok.actions()[0].equals("default"),"OK retains native Home behavior");t.scope(1);check(t.rows.size()==1,"global OK never offered");
  t.capture();check(!t.learn(new KeyEvent(0,24,115)),"volume remains native during capture");tap(t,19,2);check(t.bindings.size()==2,"arrows cannot be added");
  KeyEvent gamepad=new KeyEvent(0,100,9);gamepad.source=InputDevice.SOURCE_GAMEPAD;check(!t.learn(gamepad),"gamepad excluded");
  t.learn(new KeyEvent(1,4,0));check(t.capturing,"orphan Back release cannot cancel");tap(t,4,0);check(!t.capturing&&t.roots>0,"Back cancels learning");
  t.delete(0);check(t.bindings.size()==1&&t.bindings.get(0).code==23,"delete immediately removes only chosen configuration");
  System.out.println("PASS: actual editor learns on release, defaults to Everywhere, keeps OK local, preserves duplicate bindings, handles scope/gestures and confirms deletion");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);p=root/'io/tvos';p.mkdir(parents=True);(p/'Test.java').write_text(harness)
 q=p/'remote';q.mkdir();(q/'RemoteBindings.java').write_text(Path('app/src/io/tvos/remote/RemoteBindings.java').read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'io.tvos.Test'],check=True)
