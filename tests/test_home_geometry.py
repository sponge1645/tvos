"""Test mutable geometry inputs, disabled slots and retained measurement objects."""
from pathlib import Path
import subprocess,tempfile
from home_geometry_fixture import write
harness='''import io.tvos.home.*;import android.graphics.*;
public class GeometryHarness {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.01f,a+" != "+b);}
 public static void main(String[] args){HomeGeometry g=new HomeGeometry();RectF[] rects=new RectF[11];for(int i=0;i<rects.length;i++)rects[i]=new RectF();Typeface narrow=new Typeface(),wide=new Typeface();wide.factor=2;
 int paints=Paint.allocations,metrics=Paint.FontMetrics.allocations;int[] order={0,4,5};
 for(int i=0;i<1000;i++)g.actions(rects,order,id->true,"12:34",narrow,60,1780,100,12,1);
 check(Paint.allocations==paints&&Paint.FontMetrics.allocations==metrics,"steady layouts allocate neither Paint nor FontMetrics");float original=rects[9].width();g.actions(rects,order,id->true,"1:23",narrow,60,1780,100,12,1);check(rects[9].width()<original,"clock string changes geometry");g.actions(rects,order,id->true,"12:34",wide,60,1780,100,12,1);check(rects[9].width()>original,"font changes geometry");
 g.actions(rects,new int[]{5,4,0},id->id!=4,"12:34",narrow,60,1780,100,12,1);check(rects[9].width()==0,"disabled clock clears old bounds");near(rects[5].right,1780);near(rects[10].right,1708);
 g.actions(rects,order,id->false,"12:34",narrow,60,1780,100,12,1);for(int id:order)check(rects[5+id].width()==0,"hidden actions clear geometry");
 Paint normal=new Paint(),active=new Paint();String[] labels={"A","Long"};g.tabs(rects,new int[]{1,0},id->true,labels,normal,active,false,1);near(rects[1].left,40);near(rects[0].left,40+60+52+4);g.tabs(rects,new int[]{1,0},id->id==0,labels,normal,active,true,1);check(rects[1].width()==0,"hidden tab clears old bounds");near(rects[0].top,40);
 g.tabs(rects,new int[]{1,0},id->true,new String[]{"A much longer genre label","Another very long title"},normal,active,false,1,350,650);
 near(rects[1].left,350);near(rects[0].right,650);near(rects[0].top,HomeLayout.TOP);near(rects[0].height(),HomeLayout.BUTTON);check(rects[1].right+4<=rects[0].left+.01f,"constrained labels keep the gap and cannot overlap corner actions");
 check(Paint.allocations==paints+2&&Paint.FontMetrics.allocations==metrics,"measurement objects remain retained across updates");
 System.out.println("PASS: geometry responds to text/typeface/order/visibility/compact inputs without repeated Paint or FontMetrics allocation");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);write(root,android=True);(root/'GeometryHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'GeometryHarness'],check=True)
