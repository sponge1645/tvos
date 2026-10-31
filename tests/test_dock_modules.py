"""Use Dock transactions and caption timing with unrelated payloads, outside the launcher package."""
from pathlib import Path
import subprocess,tempfile
for name in ['HomeDockEditor','CaptionTransition','ResumeRowRenderer']:
 s=Path('app/src/io/tvos/home/dock',name+'.java').read_text()
 assert 'package io.tvos.home.dock;' in s
 for forbidden in ['Prefs.','HomeApps.','Recs.','DockBackdrop.','getSharedPreferences','ContentResolver']:
  assert forbidden not in s,(name,forbidden)
files={
'android/os/Handler.java':'''package android.os;public class Handler {Runnable queued;public void postDelayed(Runnable r,long delay){if(delay!=500)throw new AssertionError();queued=r;}public void removeCallbacks(Runnable r){if(queued==r)queued=null;}public void hold(){Runnable r=queued;queued=null;if(r!=null)r.run();}}''',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,KEYCODE_BACK=4;public int getAction(){return 0;}public int getKeyCode(){return 0;}public int getRepeatCount(){return 0;}public boolean isCanceled(){return false;}}''',
'external/DockModulesTest.java':'''package external;import java.util.*;import io.tvos.home.dock.*;import android.os.Handler;
public class DockModulesTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Host implements HomeDockEditor.Host<String> {
  List<String> items=new ArrayList<>(Arrays.asList("one","two","three"));int index,saved;
  public boolean eligible(){return true;}public List<String> items(){return items;}public int index(){return index;}public void begin(){}public void move(int from,int to){Collections.swap(items,from,to);index=to;}
  public void finish(List<String> before){if(before!=null)items=before;}public void saved(){saved++;}public void canceled(){}
 }
 public static void main(String[] args){
  Handler handler=new Handler();Host host=new Host();List<List<String>> saves=new ArrayList<>();HomeDockEditor<String> editor=new HomeDockEditor<>(handler,host,items->saves.add(new ArrayList<>(items)));
  editor.arm();handler.hold();editor.move(1);editor.finish(true);check(saves.size()==1&&saves.get(0).equals(Arrays.asList("two","one","three"))&&host.saved==1,"unrelated payload saves via injected callback");
  editor.arm();handler.hold();editor.move(1);editor.pause();check(!editor.editing()&&host.items.equals(saves.get(0))&&saves.size()==1,"pause restores snapshot without saving");
  CaptionTransition<String> captions=new CaptionTransition<>(Objects::equals,s->s.startsWith("text:"));captions.show("text:Film",true,1000);check(captions.current(1699)==null&&captions.nextDelay(1000)==700&&!captions.tick(1000),"delayed captions sleep before deadline");
  captions.show("text:Film",true,1300);check(captions.nextDelay(1300)==400,"same caption never postpones deadline");captions.tick(1910);check(captions.current(1910).equals("text:Film")&&Math.abs(captions.opacity(1910)-.5f)<.001f,"delayed fade reaches midpoint");
  captions.show("logo",true,1910);captions.tick(2170);check(captions.current(2170).equals("logo")&&captions.opacity(2170)==0,"replacement starts after outgoing fade");captions.tick(2590);check(!captions.tick(2591)&&captions.opacity(2591)==1,"completed fade becomes idle");captions.show(null,false,2600);check(captions.current(2600)==null&&captions.nextDelay(2600)==Long.MAX_VALUE,"immediate clear releases caption");
  System.out.println("PASS: generic Dock payload/save/rollback and independent idle-friendly caption transitions");
 }
}'''}
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in ['HomeDockEditor','CaptionTransition']:files['io/tvos/home/dock/'+name+'.java']=Path('app/src/io/tvos/home/dock',name+'.java').read_text()
 for name,text in files.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.DockModulesTest'],check=True)
