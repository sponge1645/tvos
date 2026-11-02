"""Exercise the actual retained seven-layer invalidation set."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/HomeView.java').read_text()
harness='''public class BuffersHarness {
 static class View {int invalidations;void invalidate(){invalidations++;}}
 final View revealBackdrop=new View(),sky=new View(),heroLayer=new View(),status=new View(),platter=new View(),tiles=new View(),drawerLayer=new View(),sheetLayer=new View(),searchGlassLayer=new View();
 final View[] redrawLayers={revealBackdrop,sky,heroLayer,status,platter,tiles,drawerLayer};
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+block(source,'    private void invalidateAll(')+'''
 public static void main(String[] args){BuffersHarness v=new BuffersHarness();v.invalidateAll();for(View layer:v.redrawLayers)check(layer.invalidations==1,"every original layer invalidated");check(v.sheetLayer.invalidations==0&&v.searchGlassLayer.invalidations==0,"unrelated layers unchanged");
 System.out.println("PASS: actual retained seven-layer invalidation, unrelated layers unchanged");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'BuffersHarness.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,str(root/'BuffersHarness.java')],check=True)
 subprocess.run(['java','-cp',tmp,'BuffersHarness'],check=True)
