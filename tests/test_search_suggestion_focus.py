"""Exercise real search key handling across suggestions, Clear, input and posters."""
from pathlib import Path
from tmdb_metadata_fixture import block
import tempfile,subprocess
s=Path('app/src/io/tvos/SearchActivity.java').read_text()
h='''package io.tvos;import java.util.*;import android.view.KeyEvent;
public class SuggestionHarness {
 boolean transitioning;Object detail;int selected=-1,column;List<Target> targets=new ArrayList<>();List<Integer> posterTargets=new ArrayList<>();Query query=new Query();
 static class Query {boolean focused;boolean hasFocus(){return focused;}}
 static class Bounds {float x;Bounds(float x){this.x=x;}float centerX(){return x;}}
 static class Target {int poster;Bounds bounds;Runnable action=()->{};Target(float x,int poster){bounds=new Bounds(x);this.poster=poster;}}
 void input(){selected=-1;query.focused=true;}void select(int i,boolean preserve){selected=i;query.focused=false;}void selectFirstPoster(){if(!posterTargets.isEmpty())select(posterTargets.get(0),false);}
'''+block(s,'    private void focusSuggestionAbove(')+block(s,'    private boolean key(')+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 void press(int code){key(new KeyEvent(code));}
 public static void main(String[] args){SuggestionHarness h=new SuggestionHarness();h.targets.add(new Target(150,-1));h.targets.add(new Target(500,-1));h.targets.add(new Target(1755,-1));for(int i=0;i<12;i++){h.posterTargets.add(h.targets.size());h.targets.add(new Target(210+(i%6)*300,i));}
 h.select(3,false);h.press(19);check(h.selected==0);h.press(19);check(h.query.focused);h.press(20);check(h.selected==0);h.select(8,false);h.press(19);check(h.selected==2);h.press(21);check(h.selected==1);h.press(21);check(h.selected==0);h.select(9,false);h.press(19);check(h.selected==3);h.select(0,false);h.press(20);check(h.selected==3);
 SuggestionHarness empty=new SuggestionHarness();empty.targets.add(new Target(200,0));empty.posterTargets.add(0);empty.select(0,false);empty.press(19);check(empty.query.focused);System.out.println("PASS: poster Up reaches nearby recent/Clear button, row Up reaches input, lower rows stay in grid and absent suggestions reach input");}}
'''
with tempfile.TemporaryDirectory() as d:
 r=Path(d);(r/'SuggestionHarness.java').write_text(h);(r/'SearchGridNavigation.java').write_text(Path('app/src/io/tvos/SearchGridNavigation.java').read_text());p=r/'android/view/KeyEvent.java';p.parent.mkdir(parents=True);p.write_text('package android.view;public class KeyEvent {public static final int KEYCODE_DPAD_UP=19,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_CENTER=23,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160,ACTION_DOWN=0;int code;public KeyEvent(int c){code=c;}public int getKeyCode(){return code;}public int getAction(){return 0;}public int getRepeatCount(){return 0;}}')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,r.rglob('*.java'))],check=True);subprocess.run(['java','-cp',d,'io.tvos.SuggestionHarness'],check=True)
