"""Execute production Home mode persistence, legacy migration and independence from category visibility."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
prefs=Path('app/src/io/tvos/Prefs.java').read_text()
harness='''import java.util.*;public class ModeTest {
 static Object app=new Object();static boolean[] categories={true,true,false,true,false};static int version;
 static class Store {Map<String,Object> data=new HashMap<>();String getString(String k,String d){return (String)data.getOrDefault(k,d);}boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}}static Store store=new Store();static Store sp(){return store;}
 static void put(String k,String v){store.data.put(k,v);version++;}static int categoryCount(){int n=0;for(boolean b:categories)if(b)n++;return n;}static boolean categoryEnabled(int id){return categories[id];}static int firstCategory(){for(int i=0;i<categories.length;i++)if(categories[i])return i;return -1;}static void setCategoryEnabled(int i,boolean on){categories[i]=on;version++;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
'''+''.join(block(prefs,sig) for sig in ['    static boolean homeContinue(', '    static void setHomeContinue('])+'''
 public static void main(String[] args){
  check(!homeContinue()&&categoryCount()==3&&firstCategory()==0,"default recommendations retain categories");
  store.data.put("dock_continue",true);check(homeContinue(),"legacy resume switch migrates without resetting choice");
  setHomeContinue(true);check(categoryCount()==3&&firstCategory()==0&&categoryEnabled(1),"continue does not hide enabled categories");check(categoryCount()==3&&categoryEnabled(1),"raw category choices remain intact");
  setHomeContinue(false);check(!homeContinue()&&categoryCount()==3&&categoryEnabled(1),"explicit recommendations overrides legacy resume flag");
  Arrays.fill(categories,false);setHomeContinue(true);check(categoryCount()==0,"continue does not rewrite all-disabled layout");setHomeContinue(false);check(!categoryEnabled(0)&&categoryCount()==0,"recommendations never enables a category");
  app=null;check(!homeContinue(),"uninitialized preferences keep safe default");System.out.println("PASS: Home mode default, legacy migration, explicit override, category independence and all-off preservation");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'ModeTest.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'ModeTest'],check=True)
