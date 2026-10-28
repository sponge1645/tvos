"""Exercise the real selection methods used internally by recommendation refresh and memory trimming."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/Recs.java').read_text()
methods='\n'.join(block(source,sig) for sig in ['    List<Pick> now(String kind)', '    private static String franchise(', '    List<Pick> wanted(', '    List<Pick> showcase(', '    void trim(', '    static String key('])
harness='''package io.tvos;import java.util.*;
public class SelectionHarness {
'''+block(source,'    static final class Pick {')+'''
 final Object context=new Object();boolean configured=true;final List<Pick> picks=new ArrayList<>();
 boolean hasKey(Object context){return configured;}
 static class Cache {Set<String> kept;boolean hasImage(Pick p){return p.backdrop!=null;}void retain(Set<String> paths){kept=new HashSet<>(paths);}}
 final Cache backdropCache=new Cache();
 static Pick pick(int id,String kind,String title){Pick p=new Pick();p.id=id;p.kind=kind;p.title=title;p.backdrop="/"+kind+id;p.detailed=true;return p;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+methods+'''
 public static void main(String[] args){
  SelectionHarness h=new SelectionHarness();check(h.now(null).isEmpty()&&h.wanted().isEmpty(),"empty catalog");
  for(int i=0;i<7;i++){h.picks.add(pick(i,"movie","Movie "+i));h.picks.add(pick(i,"tv","Series "+i));}
  for(String kind:new String[]{null,"movie","tv"}){List<Pick> selection=h.now(kind);check(selection.size()==3,"three hourly items");if(kind!=null)for(Pick p:selection)check(kind.equals(p.kind),"kind filter");}
  Set<String> unique=new HashSet<>();for(Pick p:h.wanted())check(unique.add(key(p)),"wanted combines tabs without duplicate type/id");
  Set<String> expected=new HashSet<>();for(Pick p:h.now(null))expected.add(p.backdrop);h.trim();check(h.backdropCache.kept.equals(expected),"memory trim retains hourly backdrops");
  h.picks.clear();h.picks.add(pick(1,"movie","Law & Order: A"));h.picks.add(pick(2,"movie","LAW & ORDER - B"));h.picks.add(pick(3,"movie","Law & Order: C"));
  check(h.now("movie").size()==1&&h.showcase().size()==1,"franchise dedup remains used by refresh/showcase");
  h.picks.add(pick(1,"tv","Other series"));Set<String> keys=new HashSet<>();for(Pick p:h.wanted())keys.add(key(p));check(keys.contains("movie:1")&&keys.contains("tv:1"),"movie/series IDs remain distinct");
  h.configured=false;check(h.now(null).isEmpty()&&h.wanted().isEmpty()&&h.showcase().isEmpty(),"missing API key suppresses selections");
  System.out.println("PASS: production hourly selection, wanted/showcase/franchise identity and memory-trim dependencies");
 }
}'''
with tempfile.TemporaryDirectory(prefix='tvos-recs-selection-') as tmp:
 root=Path(tmp);p=root/'io/tvos/SelectionHarness.java';p.parent.mkdir(parents=True);p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SelectionHarness'],check=True)
