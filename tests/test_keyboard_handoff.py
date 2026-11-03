"""State policy and atomic settings for temporary text-keyboard delegation."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/remote/KeyboardHandoff.java').read_text()
harness='''import io.tvos.remote.KeyboardHandoff;import static io.tvos.remote.KeyboardHandoff.Result.*;import java.io.*;
public class Test {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  String id="com.google.android.inputmethod.latin/.LatinIME";
  check(KeyboardHandoff.validId(id)&&!KeyboardHandoff.validId("x/'$(id)'")&&!KeyboardHandoff.validId(null),"only component ids enter shell");
  check(KeyboardHandoff.shown("  mShowRequested=true mInputShown=false\\n")==Boolean.FALSE,"Android 11 manager line");
  check(KeyboardHandoff.shown("mInputShown=true")==Boolean.TRUE&&KeyboardHandoff.shown("unknown")==null,"supported/unknown dumps");
  KeyboardHandoff p=new KeyboardHandoff(id,0);
  check(p.step(id,"mInputShown=true",400,true)==WAIT,"old IME visibility ignored");
  check(p.step(id,"mInputShown=false",900,true)==WAIT,"binding gap ignored");
  check(p.step(id,"mInputShown=true",1200,true)==WAIT,"text keyboard shown");
  check(p.step(id,"mInputShown=false",1600,true)==WAIT,"close debounce");
  check(p.step(id,"mInputShown=true",2000,true)==WAIT,"switching fields cancels close");
  check(p.step(id,"mInputShown=false",2400,true)==WAIT&&p.step(id,"mInputShown=false",3099,true)==WAIT,"stable hidden duration required");
  check(p.step(id,"mInputShown=false",3100,true)==RESTORE,"hidden keyboard returns to tvOS");
  check(p.step("manual/.Keyboard","mInputShown=false",4000,true)==RELEASE,"manual keyboard wins");
  p=new KeyboardHandoff(id,0);check(p.step(id,"mInputShown=false",10000,true)==RESTORE,"failed show does not strand Gboard");
  check(p.step(id,"unknown",1200,true)==RESTORE,"unsupported firmware stops handoff");
  check(p.step(id,"mInputShown=true",1200,false)==RESTORE,"screen-off returns assignments");
  check(p.step(id,"mInputShown=true",1800000,true)==RELEASE,"monitoring is bounded without interrupting typing");
  File dir=new File(args[0]);check(KeyboardHandoff.readTarget(dir).isEmpty(),"missing settings");
  KeyboardHandoff.writeTarget(dir,id);check(KeyboardHandoff.readTarget(dir).equals(id),"cross-process atomic setting");
  try{KeyboardHandoff.writeTarget(dir,"bad; command");throw new AssertionError();}catch(IllegalArgumentException expected){}
  check(KeyboardHandoff.readTarget(dir).equals(id),"invalid settings preserve old target");
  try(FileOutputStream out=new FileOutputStream(new File(dir,"text-keyboard.bin"))){out.write(0);}
  check(KeyboardHandoff.readTarget(dir).isEmpty(),"truncated settings safely fall back");
  System.out.println("PASS: IME handoff binding/close debounce, manual changes, show failure, unknown firmware, screen-off, bounded lifetime and atomic component storage");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory);p=root/'io/tvos/remote';p.mkdir(parents=True);(p/'KeyboardHandoff.java').write_text(source);(root/'Test.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',directory,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',directory,'Test',directory],check=True)
