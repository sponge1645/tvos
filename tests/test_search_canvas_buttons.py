"""Execute the rebuilt search's capsule drawing on the recording graphics adapter."""
from pathlib import Path
import subprocess,tempfile
from home_renderers_fixture import files
from settings_strings_fixture import write
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SearchActivity.java').read_text()
f=files()
f['io/tvos/SearchButtonHarness.java']='''package io.tvos;
import android.graphics.*;import android.text.*;public class SearchButtonHarness {
 final Paint paint=new Paint(); final TextPaint textPaint=new TextPaint();boolean night=true;
 int ink(){return night?0xFFF5F5F7:0xFF1D1D1F;}
'''+block(source,'    private static final class Target {')+block(source,'    private void capsule(')+'''
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args){SearchButtonHarness h=new SearchButtonHarness();
 for(float width:new float[]{110,170,800})for(float height:new float[]{58,64,72}){
  Target t=new Target(new RectF(80,275,80+width,275+height),"Watch",-1,()->{});Canvas c=new Canvas();h.capsule(c,t,1);
  Canvas.Entry round=c.entries.stream().filter(e->e.kind.equals("round")).findFirst().get();
  check(round.l==80&&round.t==275&&round.r==80+width&&round.b==275+height,"full capsule bounds survive drawing");
  check(Math.abs(round.radius-height/2)<.01,"both ends remain semicircular");
  check(c.entries.stream().anyMatch(e->e.kind.equals("text")&&e.text.equals("Watch")),"button label drawn");
 }
 System.out.println("PASS: search history, Clear and provider buttons draw complete capsules in one canvas");
 }}'''
with tempfile.TemporaryDirectory() as d:
 root=Path(d);write(root)
 for name,content in f.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',d,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',d,'io.tvos.SearchButtonHarness'],check=True)
