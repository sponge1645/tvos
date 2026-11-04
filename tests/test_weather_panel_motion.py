"""Execute close reversal and reduced-motion behavior using the production weather dialog method."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/HomeWeatherPanel.java').read_text()
method=block(source,'    private void close(').replace('new android.view.animation.AccelerateInterpolator(1.2f)','new Object()')
harness='''public class Harness {
 boolean closing,reduced;int dismissals;boolean reduced(){return reduced;}void dismiss(){dismissals++;}
 class Animator {int cancelled;float alpha=1,scaleX=1,scaleY=1,y;long duration;Runnable end;
 void cancel(){cancelled++;}Animator alpha(float v){alpha=v;return this;}Animator scaleX(float v){scaleX=v;return this;}Animator scaleY(float v){scaleY=v;return this;}Animator translationY(float v){y=v;return this;}Animator setDuration(long v){duration=v;return this;}Animator setInterpolator(Object i){return this;}Animator withEndAction(Runnable r){end=r;return this;}void start(){}}
 class Forecast {Animator animator=new Animator();Animator animate(){return animator;}int getWidth(){return 1920;}int getHeight(){return 1080;}}Forecast forecast=new Forecast();
'''+method+'''
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){Harness h=new Harness();h.close();Animator a=h.forecast.animator;
 check(a.cancelled==1&&a.alpha==0&&a.scaleX==.97f&&a.scaleY==.97f&&a.y==12&&a.duration==220&&h.dismissals==0);
 h.close();check(a.cancelled==1);a.end.run();check(h.dismissals==1);
 h=new Harness();h.reduced=true;h.close();check(h.dismissals==1&&h.forecast.animator.cancelled==1&&h.forecast.animator.end==null);
 System.out.println("PASS: weather closes from current animation state, repeated Back is ignored and Reduce Motion dismisses directly");
 }}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);(root/'Harness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,str(root/'Harness.java')],check=True)
 subprocess.run(['java','-cp',d,'Harness'],check=True)
