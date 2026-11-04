"""Execute page transitions, cancellation and reduced-motion behavior."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
s=Path('app/src/io/tvos/SearchActivity.java').read_text()
h='''public class TransitionHarness {
 boolean transitioning,closed,running=true,reduce;float scale=1;int changes;Page page=new Page();Animator scrolling;
 boolean reduced(){return reduce;}
 static class Page {float alpha=1,x;Animator a=new Animator(this);Animator animate(){return a;}void setAlpha(float v){alpha=v;}void setTranslationX(float v){x=v;}}
 static class Animator {Page p;Runnable end;boolean active;float alpha,x;long duration;Animator(Page p){this.p=p;}Animator alpha(float v){alpha=v;return this;}Animator translationX(float v){x=v;return this;}Animator setDuration(long v){duration=v;return this;}Animator withEndAction(Runnable r){end=r;return this;}Animator setInterpolator(Object o){return this;}void start(){active=true;}void cancel(){end=null;active=false;}void finish(){if(!active)return;active=false;p.alpha=alpha;p.x=x;Runnable r=end;end=null;if(r!=null)r.run();}}
'''+block(s,'    private void transitionPage(')+block(s,'    private void resetPageTransition(')+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){TransitionHarness h=new TransitionHarness();h.transitionPage(()->h.changes++,true);check(h.transitioning&&h.changes==0&&h.page.a.duration==100);h.transitionPage(()->h.changes+=100,true);h.page.a.finish();check(h.changes==1&&h.page.alpha==0&&h.page.x==28&&h.page.a.duration==220);h.page.a.finish();check(!h.transitioning&&h.page.alpha==1&&h.page.x==0);h.transitionPage(()->h.changes++,false);h.page.a.finish();check(h.page.x==-28);h.page.a.finish();h.transitionPage(()->h.changes+=100,true);h.resetPageTransition();h.page.a.finish();check(h.changes==2&&!h.transitioning&&h.page.alpha==1&&h.page.x==0);h.reduce=true;h.transitionPage(()->h.changes++,true);check(h.changes==3&&!h.transitioning);System.out.println("PASS: deferred detail swap, forward/back fade/slide, repeated input, cancellation and Reduce Motion");}}
'''
with tempfile.TemporaryDirectory() as d:
 r=Path(d);(r/'TransitionHarness.java').write_text(h);p=r/'android/view/animation/DecelerateInterpolator.java';p.parent.mkdir(parents=True);p.write_text('package android.view.animation;public class DecelerateInterpolator {public DecelerateInterpolator(float f){}}')
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,r.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'TransitionHarness'],check=True)
