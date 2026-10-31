"""Use the settings models from another package without Prefs, HomeApps or Android stubs."""
from pathlib import Path
import subprocess,tempfile
modules=['SettingsOrderEditor','SettingsDockEditor','SettingsActions','SettingsScreen']
for name in modules+['SettingsBackground','SettingsPopupBackdrop']:
 p=Path('app/src/io/tvos/settings')/(name+'.java');s=p.read_text()
 assert 'package io.tvos.settings;' in s and 'import io.tvos.' not in s
 assert not Path('app/src/io/tvos',name+'.java').exists()
 if name in modules:assert 'Prefs.' not in s and 'HomeApps.' not in s and 'android.' not in s
harness='''package external;
import java.util.*;import io.tvos.settings.*;import static io.tvos.settings.SettingsScreen.*;
public class ModulesTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static class Storage implements SettingsOrderEditor.Store {
  List<Integer> order=new ArrayList<>(Arrays.asList(3,1,2));Set<Integer> enabled=new HashSet<>(Arrays.asList(3,2));int writes;
  public int[] order(){return order.stream().mapToInt(Integer::intValue).toArray();}public boolean enabled(int id){return enabled.contains(id);}public int count(){return enabled.size();}
  public void setEnabled(int id,boolean on){if(on)enabled.add(id);else enabled.remove(id);}public void saveOrder(List<Integer> values){order=new ArrayList<>(values);writes++;}
 }
 public static void main(String[] args){
  Storage store=new Storage();SettingsOrderEditor order=new SettingsOrderEditor(store);check(order.rebuild(-1)==0&&order.get(1)==2&&order.get(2)==1,"active/inactive grouping uses injected storage");
  check(order.toggle(2)==2&&store.enabled(1),"enabling persists through adapter");check(order.move(2,-1)==1&&store.order.equals(Arrays.asList(3,1,2)),"order writes use adapter");check(order.toggle(99)==99&&store.writes==2,"stale row never writes");
  List<List<String>> saved=new ArrayList<>();SettingsDockEditor dock=new SettingsDockEditor(2,values->saved.add(new ArrayList<>(values)));
  dock.open(Arrays.asList("a"),Arrays.asList(new String[]{"a","A"},new String[]{"b","B"},new String[]{"c","C"}));
  check(dock.toggle(2)==1&&saved.get(0).equals(Arrays.asList("a","c")),"Dock saves through injected consumer");check(dock.toggle(2)==-1&&saved.size()==1,"injected capacity preserves rejected selection");check(dock.move(1,-1)==0&&saved.get(1).equals(Arrays.asList("c","a")),"Dock reorder persists and follows selected package");
  check(SettingsActions.activation(GENERAL,7,0,false,6,false).targetScreen==HOME_SCREEN,"navigation policy has no UI dependency");SettingsActions.Target back=SettingsActions.back(HOME_SCREEN,0,GENERAL,6);check(back.screen==GENERAL&&back.row==7,"stable back destination remains public across package boundary");
  check(HOME_SCREEN==24&&PICK_VPN==-1&&GENERAL==6,"screen identities remain stable");
  System.out.println("PASS: cross-package models with independent stores, persistence/capacity, pure navigation and stable screen identities");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name in modules:
  p=root/'io/tvos/settings'/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(Path('app/src/io/tvos/settings',name+'.java').read_text())
 p=root/'external/ModulesTest.java';p.parent.mkdir();p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'external.ModulesTest'],check=True)
