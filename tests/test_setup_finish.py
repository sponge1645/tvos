"""Actual native two-action TV and Home screen presentation/input."""
from pathlib import Path
import runpy,tempfile,subprocess,re
stubs=dict(runpy.run_path('tests/test_setup_language.py')['stubs'])
stubs.pop('io/tvos/LanguageTest.java')
src=Path('app/src/io/tvos/SetupFinishView.java').read_text();ids=sorted(set(re.findall(r'R.string.(settings_\w+)',src)))
stubs['io/tvos/R.java']='package io.tvos;class R {static class string {'+''.join('static final int '+k+'='+str(i+1)+';' for i,k in enumerate(ids))+'}}'
stubs['io/tvos/AppStrings.java']='package io.tvos;class AppStrings {static String get(int id){return "text "+id;}}'
stubs['io/tvos/FinishTest.java']='''package io.tvos;import android.content.Context;import android.graphics.Canvas;import android.view.KeyEvent;class FinishTest {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}static void ok(SetupFinishView v){v.onKeyDown(23,new KeyEvent(0,false));v.onKeyUp(23,new KeyEvent(0,false));}public static void main(String[] args){Context.reduced=true;int[] edit={0},next={0};SetupFinishView tv=new SetupFinishView(new Context(),true,"System TV",()->edit[0]++,()->next[0]++);Canvas c=new Canvas();tv.onDraw(c);check(c.texts.contains("System TV")&&c.paths==1,"detected app and native TV artwork");tv.application("Chosen TV");c=new Canvas();tv.onDraw(c);check(c.texts.contains("Chosen TV"),"choice label updates without navigation");tv.onKeyUp(23,new KeyEvent(0,false));check(edit[0]==0,"orphan release ignored");tv.onKeyDown(23,new KeyEvent(0,false));tv.onWindowFocusChanged(false);tv.onKeyUp(23,new KeyEvent(0,false));check(edit[0]==0,"focus loss cancels");ok(tv);check(edit[0]==1&&next[0]==0,"first button edits only");tv.onKeyDown(20,new KeyEvent(0,false));ok(tv);ok(tv);check(next[0]==1,"continue once");int[] home={0},later={0};SetupFinishView h=new SetupFinishView(new Context(),false,"",()->home[0]++,()->later[0]++);ok(h);ok(h);check(home[0]==1&&later[0]==0,"Home action once");h.onWindowFocusChanged(true);h.onKeyDown(20,new KeyEvent(0,false));ok(h);check(later[0]==1,"not now remains optional");h.onDetachedFromWindow();System.out.println("PASS: actual TV/Home layout, live app label, edit/continue distinction, optional Home and focus/release guards");}}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,code in stubs.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code)
 for name in ['SetupFinishView.java','setup/SetupArtwork.java','Spring.java']:
  p=root/'io/tvos'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text((Path('app/src/io/tvos')/name).read_text())
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.FinishTest'],check=True,timeout=15)
