from settings_strings_fixture import write as write_strings
"""Exercise production profile navigation, persistence and animation scheduling without Android."""
from pathlib import Path
import os
import subprocess
import tempfile

source = Path('app/src/io/tvos/ProfileActivity.java').read_text()


def method(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


stubs = {
    'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;public final Prefs prefs=new Prefs();
 public Prefs getSharedPreferences(String n,int m){return prefs;}
 public static class Prefs {
  int avatar=-1,legacy=-1;public boolean reduced;
  public boolean contains(String k){return avatar!=-1;}
  public int getInt(String k,int d){return k.equals("profile_avatar_v2")?avatar:legacy;}
  public boolean getBoolean(String k,boolean d){return reduced;}
  public Prefs edit(){return this;}public Prefs putInt(String k,int v){if(k.equals("profile_avatar_v2"))avatar=v;else legacy=v;return this;}public void apply(){}
 }
}''',
    'android/graphics/Paint.java': '''package android.graphics;public class Paint {
 public static final int ANTI_ALIAS_FLAG=1;public static class FontMetrics {public float ascent,descent;}
 public Paint(int f){}public void setShader(Object x){}public void setColor(int c){}
 public void setTypeface(Typeface t){}public void setTextSize(float s){}
 public void getFontMetrics(FontMetrics f){}public float measureText(String s){return 1;}
}''',
    'android/graphics/Canvas.java': '''package android.graphics;public class Canvas {
 public void drawCircle(float x,float y,float r,Paint p){}
 public void drawText(String s,float x,float y,Paint p){}
}''',
    'android/graphics/Typeface.java': 'package android.graphics;public class Typeface {public static final Typeface DEFAULT=new Typeface();}',
}

harness = '''package io.tvos;
import android.content.Context;
class Ui {static boolean ru;}
class ProfileAvatarRender {void prepare(int p){}void prepareTransition(int p){}}
class BaseView {
 final Context context=new Context();static final int MODE_PRIVATE=0;
 Context getContext(){return context;}
 boolean onKeyDown(int code,KeyEvent e){return false;}boolean onKeyUp(int code,KeyEvent e){return false;}
}
class KeyEvent {
 static final int KEYCODE_DPAD_UP=1,KEYCODE_DPAD_DOWN=2,KEYCODE_DPAD_LEFT=3,KEYCODE_DPAD_RIGHT=4,
 KEYCODE_DPAD_CENTER=5,KEYCODE_ENTER=6,KEYCODE_NUMPAD_ENTER=7,KEYCODE_BACK=8,KEYCODE_ESCAPE=9;
 int repeat;boolean cancelled;
 int getRepeatCount(){return repeat;}boolean isCanceled(){return cancelled;}
}
class Choreographer {
 interface FrameCallback {void doFrame(long now);}
 static final Choreographer instance=new Choreographer();int pending;
 static Choreographer getInstance(){return instance;}
 void postFrameCallback(Object c){pending++;}void removeFrameCallback(Object c){pending=0;}
}
class Click {Click(Context c){}void move(){}void open(){}void back(){}void release(){}}
class Bundle {
 final java.util.Map<String,Object> data=new java.util.HashMap<>();
 void putBoolean(String k,boolean v){data.put(k,v);}void putInt(String k,int v){data.put(k,v);}
}
public class ProfileHarness extends BaseView implements Choreographer.FrameCallback {
 final Spring[] buttons={new Spring(.26f,.85f,1),new Spring(.26f,.85f,0)};
 final Spring[] avatarFocus=new Spring[ProfileAvatar.COUNT];
 final Spring chooser=new Spring(.38f,1,0),entrance=new Spring(.55f,1,0);
 final Spoken spoken=new Spoken();final Shadows shadows=new Shadows();
 static class Spoken {void readAgain(){}void focusMoved(){}}
 static class Shadows {void clear(){}}
 final ProfileAvatarRender avatars=new ProfileAvatarRender();
 final Spring pageTransition=new Spring(.24f,1,1);
 int page,previousPage;
 int selected,focus,avatarIndex,finished,draws;long lastFrame;
 boolean choosing,running,scheduled,okDown,backDown;Click click;
 ProfileHarness(){for(int i=0;i<avatarFocus.length;i++)avatarFocus[i]=new Spring(.26f,.85f,0);}
 void finish(){finished++;}void updateText(){}void invalidate(){draws++;}
 void requestFocus(){}
''' + ''.join(method(signature) for signature in [
    '        void save(', '        void resume(', '        void pause(',
    '        private void wake(', '        @Override public void doFrame(',
    '        private void retarget(', '        private void activate(',
    '        private void back(', '        private void changePage(', '        private void move(',
    '        @Override public boolean onKeyDown(', '        @Override public boolean onKeyUp(',
]) + '''
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 void drain(){for(int i=1;i<400 && scheduled;i++){
  Choreographer.instance.pending--;doFrame(i*16_666_667L);
 }}
 public static void main(String[] args){
  ProfileHarness h=new ProfileHarness();h.resume();h.wake();h.wake();
  check(Choreographer.instance.pending==1,"one pending frame despite repeated wake");
  h.drain();check(!h.scheduled && Choreographer.instance.pending==0,"idle screen stops frame callbacks");
  check(ProfileAvatar.selected(h.context)==-1,"opening page leaves default clock avatar untouched");
  Context old=new Context();
  int[] mapped={1,3,11,4,8,0,6};
  for(int i=0;i<mapped.length;i++){
   old.prefs.putInt("profile_avatar",i);
   check(ProfileAvatar.selected(old)==mapped[i],"existing avatar identity survives reordering");
  }
  ProfileAvatar.select(old,17);check(ProfileAvatar.selected(old)==17,"new selection overrides legacy");
  h.activate();check(h.choosing,"first action opens avatar selection");
  h.move(1,0);h.back();check(!h.choosing && ProfileAvatar.selected(h.context)==-1,"Back cancels without saving");
  h.activate();h.avatarIndex=5;h.move(0,1);check(h.avatarIndex==8,"third row reachable");
  h.move(0,1);check(h.avatarIndex==8,"bottom edge bounded");
  h.move(1,0);check(h.avatarIndex==15 && h.page==1,"right edge opens second page in same row");
  h.move(-1,0);check(h.avatarIndex==8 && h.page==0,"left edge returns to first page");
  h.changePage(1);h.avatarIndex=17;h.activate();
  check(!h.choosing && h.selected==17 && ProfileAvatar.selected(h.context)==17,"second page selection persists");
  h.activate();h.changePage(0);h.back();
  check(h.page==1 && h.selected==17,"cancel restores selected avatar page");
  h.context.prefs.putInt("profile_avatar_v2",99);check(ProfileAvatar.selected(h.context)==-1,"invalid saved avatar falls back");
  boolean rejected=false;try{ProfileAvatar.select(h.context,-1);}catch(IllegalArgumentException e){rejected=true;}
  check(rejected,"invalid avatar cannot be written");
  KeyEvent e=new KeyEvent();h.onKeyDown(KeyEvent.KEYCODE_ENTER,e);e.repeat=3;
  h.onKeyDown(KeyEvent.KEYCODE_ENTER,e);check(!h.choosing,"held OK does not repeatedly activate");
  h.onKeyUp(KeyEvent.KEYCODE_ENTER,e);check(h.choosing,"one release activates once");
  h.onKeyDown(KeyEvent.KEYCODE_ENTER,e);h.pause();h.onKeyUp(KeyEvent.KEYCODE_ENTER,e);
  check(h.choosing && !h.scheduled && Choreographer.instance.pending==0,"pause cancels frames and pending key press");
  h.resume();h.context.prefs.reduced=true;h.retarget(false);
  check(h.chooser.value==1 && h.entrance.value==1,"reduced motion snaps transitions");
  Bundle state=new Bundle();h.save(state);
  check(Boolean.TRUE.equals(state.data.get("choosing")) && state.data.get("avatar").equals(h.avatarIndex),"selection survives saved state");
  h.back();h.focus=1;h.activate();check(h.finished==1,"return action exits page");
  h.pause();
  System.out.println("PASS: profile arrows, cancel/save, avatar validation, held keys, pause, bounded frames, idle, reduced motion, saved state");
 }
}
'''

with tempfile.TemporaryDirectory(prefix='tvos-profile-') as directory:
    root = Path(directory)
    write_strings(root)
    for name, text in stubs.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    (root / 'ProfileHarness.java').write_text(harness)
    for name in ['Spring']:
        (root / (name + '.java')).write_text(Path('app/src/io/tvos', name + '.java').read_text())
    avatar_source = Path('app/src/io/tvos/ProfileAvatar.java').read_text()
    def avatar_method(signature):
        start = avatar_source.index(signature)
        brace = avatar_source.index('{', start)
        depth, end = 1, brace + 1
        while depth:
            depth += (avatar_source[end] == '{') - (avatar_source[end] == '}')
            end += 1
        return avatar_source[start:end]
    persistence = 'package io.tvos; import android.content.Context; class ProfileAvatar {static final int COUNT=18;private static final String KEY="profile_avatar_v2";private static final int[] LEGACY={1,3,11,4,8,0,6};'
    persistence += avatar_method('    static int selected(').replace('android.content.SharedPreferences', 'Context.Prefs')
    persistence += avatar_method('    static void select(') + '}'
    (root / 'ProfileAvatar.java').write_text(persistence)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, 'com.sun.tools.javac.Main', '-d', directory, *map(str, root.rglob('*.java'))], check=True)
    subprocess.run([java, '-cp', directory, 'io.tvos.ProfileHarness'], check=True)
