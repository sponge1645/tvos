"""Exercise page identity, catalog shrink and poster prefetch priority."""
from pathlib import Path
import subprocess,tempfile
harness='''import io.tvos.home.HomePosterWindow;import java.util.*;
public class PosterTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}static class Pick {final int id;Pick(int i){id=i;}}
 public static void main(String[]args){for(int size=0;size<42;size++)for(int per:new int[]{1,3,5})for(int requested=0;requested<45;requested++){
 List<Pick> all=new ArrayList<>();for(int i=0;i<size;i++)all.add(new Pick(i));int expectedPage=Math.min(requested,Math.max(0,(size-1)/per));int page=HomePosterWindow.clampPage(requested,size,per);check(page==expectedPage,"clamp");int from=HomePosterWindow.start(page,size,per);check(from==Math.min(size,page*per),"start");
 List<Pick> old=new ArrayList<>();List<Pick> result=HomePosterWindow.select(all,from,per,old);check(result.equals(all.subList(from,Math.min(size,from+per))),"page contents");check(HomePosterWindow.select(all,from,per,result)==result,"stable page identity");
 List<Pick> expected=new ArrayList<>(all.subList(from,Math.min(size,from+per*3)));expected.addAll(all.subList(Math.max(0,from-per),from));check(HomePosterWindow.prefetch(all,from,per).equals(expected),"current, next two, previous priority");
 if(!result.isEmpty()){Pick previous=result.get(0);all.set(from,new Pick(previous.id));List<Pick> localized=HomePosterWindow.select(all,from,per,result);check(localized!=result&&localized.get(0)==all.get(from)&&result.get(0)==previous,"same ID new localized object replaces page without mutating old list");localized.clear();check(all.size()==size,"changed page is independent mutable list");}
 }
 List<Pick> old=Arrays.asList(new Pick(1));List<Pick> empty=HomePosterWindow.select(Collections.emptyList(),0,5,old);check(empty.isEmpty()&&empty!=old,"empty catalog replaces old page");empty.add(new Pick(2));check(empty.size()==1,"empty replacement remains mutable");
 System.out.println("PASS: page bounds, stable object identity, localized replacement, mutable snapshots and prefetch order");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);(root/'HomePosterWindow.java').write_text(Path('app/src/io/tvos/home/HomePosterWindow.java').read_text());(root/'PosterTest.java').write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-Xlint:all','-Werror','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'PosterTest'],check=True)
