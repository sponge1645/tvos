"""Execute the actual Dock model and settings focus adapters, including persistence and scroll edges."""
from pathlib import Path
import re, subprocess, tempfile
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
prefs=Path('app/src/io/tvos/Prefs.java').read_text()
def method(text,signature):
 a=text.index(signature);b=text.index('{',a);d=1;i=b+1
 while d:d+=(text[i]=='{')-(text[i]=='}');i+=1
 return text[a:i]
maximum=re.search(r'static final int MAX_SHELF\s*=\s*(\d+)',Path('app/src/io/tvos/HomeApps.java').read_text()).group(1)
header='''package io.tvos;import java.util.*;import io.tvos.settings.SettingsDockEditor;
class Prefs {
 static final Map<String,String> data=new HashMap<>();static int writes;
 static class Store {String getString(String k,String fallback){return data.getOrDefault(k,fallback);}}static Store sp(){return new Store();}
 static void put(String k,String v){data.put(k,v);writes++;}
'''+method(prefs,'    static List<String> shelf()')+method(prefs,'    static void setShelf(')+'''
}
class HomeApps {
 static final int MAX_SHELF='''+maximum+''';
 static List<String> selected=new ArrayList<>();static List<String[]> candidates=new ArrayList<>();
 static List<String> shelfPackages(Object c){return selected;}static List<String[]> shelfCandidates(Object c){return candidates;}
}
public class DockEditorHarness {
 final SettingsDockEditor dockEditor=SettingsEditors.dock();int focus,screen;final io.tvos.settings.SettingsListWindow listWindow=new io.tvos.settings.SettingsListWindow(10);
 void scrollTo(int offset){listWindow.reset();for(int i=0;i<offset;i++)listWindow.move(1,9,10,100,true);}static final int SHELF_EDIT=9;
 static class Spring {float value;void snap(int v){value=v;}}final Spring listScroll=new Spring();
 Object getContext(){return this;}int rowCount(){return Math.min(10,dockEditor.size());}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static List<String[]> candidates(int count){List<String[]> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(new String[]{"app"+i,"Label "+i});return rows;}
 static List<String> selected(int count){List<String> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add("app"+i);return rows;}
 static List<String> snapshot(SettingsDockEditor editor){List<String> out=new ArrayList<>();for(int i=0;i<editor.size();i++)out.add(editor.get(i)[0]);return out;}
'''
body=''.join(method(source,s) for s in ['        private void openShelfEditor(', '        private void focusShelfRow(', '        private void toggleShelfApp(', '        private void moveShelfApp('])
tests='''
 public static void main(String[] args){
  HomeApps.selected=Arrays.asList("app4","app1");HomeApps.candidates=candidates(6);DockEditorHarness v=new DockEditorHarness();v.scrollTo(3);v.focus=2;v.openShelfEditor();
  check(v.screen==SHELF_EDIT&&v.listWindow.offset()==0&&v.focus==0&&v.listScroll.value==0,"open resets viewport and focus");
  check(snapshot(v.dockEditor).equals(Arrays.asList("app4","app1","app0","app2","app3","app5")),"selected first; remaining candidates retain source order");
  v.moveShelfApp(-1);check(Prefs.writes==0&&v.focus==0,"left edge does not save or move focus");
  v.focus=1;v.moveShelfApp(-1);check(v.focus==0&&Prefs.shelf().equals(Arrays.asList("app1","app4")),"move persists order and follows app");
  v.toggleShelfApp();check(v.focus==1&&Prefs.shelf().equals(Arrays.asList("app4"))&&v.dockEditor.get(1)[0].equals("app1"),"disable retains selection on app in disabled region");
  v.toggleShelfApp();check(v.focus==1&&Prefs.shelf().equals(Arrays.asList("app4","app1")),"enable appends to selected group");
  SettingsDockEditor reload=SettingsEditors.dock();reload.open(Prefs.shelf(),HomeApps.candidates);check(snapshot(reload).equals(snapshot(v.dockEditor)),"saved Dock order survives model recreation");
  HomeApps.selected=selected(HomeApps.MAX_SHELF);HomeApps.candidates=candidates(15);v.openShelfEditor();v.scrollTo(5);v.focus=9;int writes=Prefs.writes;
  v.toggleShelfApp();check(Prefs.writes==writes&&v.listWindow.offset()==5&&v.focus==9,"capacity failure leaves viewport and storage unchanged");
  v.scrollTo(0);v.focus=3;v.toggleShelfApp();check(v.dockEditor.get(v.listWindow.offset()+v.focus)[0].equals("app3")&&v.focus==7,"remove selected app follows its new disabled row");
  v.scrollTo(5);v.focus=9;v.toggleShelfApp();check(v.dockEditor.get(v.listWindow.offset()+v.focus)[0].equals("app14")&&v.listWindow.offset()==5&&v.focus==2,"add from lower viewport focuses new selected position");
  v.focus=0;String pkg=v.dockEditor.get(5)[0];v.moveShelfApp(-1);check(v.listWindow.offset()==4&&v.focus==0&&v.dockEditor.get(4)[0].equals(pkg),"move across top viewport edge scrolls with selected app");
  v.focusShelfRow(14);check(v.listWindow.offset()==5&&v.focus==9,"focus below viewport adjusts scroll to last visible row");
  writes=Prefs.writes;v.moveShelfApp(-1);check(Prefs.writes==writes&&v.focus==9,"disabled candidate cannot be reordered");
  SettingsDockEditor empty=SettingsEditors.dock();empty.open(Collections.emptyList(),Collections.emptyList());check(empty.size()==0&&empty.toggle(0)==-1&&empty.move(-1,1)==-1,"empty and stale rows are safe no-ops");
  SettingsDockEditor missing=SettingsEditors.dock();missing.open(Arrays.asList("missing","app2"),candidates(4));check(missing.size()==4&&missing.position("missing")==0&&missing.get(0)[0].equals("app2"),"unavailable selected package is retained without phantom candidate");
  missing.toggle(0);check(Prefs.shelf().equals(Arrays.asList("missing")),"editing visible apps preserves unavailable selection");
  HomeApps.selected=Collections.emptyList();HomeApps.candidates=candidates(2);v.openShelfEditor();v.toggleShelfApp();v.toggleShelfApp();check(Prefs.shelf().isEmpty(),"explicit empty Dock saved rather than restoring defaults");
  System.out.println("PASS: actual Dock selection, reorder, limits, persistence, reopen, unavailable apps and settings viewport/focus");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='tvos-dock-editor-') as tmp:
 root=Path(tmp)
 (root/'SettingsListWindow.java').write_text(Path('app/src/io/tvos/settings/SettingsListWindow.java').read_text())
 (root/'SettingsDockEditor.java').write_text(Path('app/src/io/tvos/settings/SettingsDockEditor.java').read_text())
 (root/'SettingsEditors.java').write_text('package io.tvos;import io.tvos.settings.SettingsDockEditor;final class SettingsEditors {'+method(Path('app/src/io/tvos/SettingsEditors.java').read_text(),'    static SettingsDockEditor dock(')+'}')
 (root/'DockEditorHarness.java').write_text(header+body+tests)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.DockEditorHarness'],check=True)
