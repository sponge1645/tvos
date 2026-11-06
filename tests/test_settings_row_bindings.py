"""Execute every production list binding against snapshots of renderer input, including overscan."""
from pathlib import Path
import subprocess,tempfile
from settings_list_renderer_fixture import geometry_sources
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
harness='''package io.tvos;import android.graphics.*;import io.tvos.settings.*;import io.tvos.settings.SettingsListRenderer.Kind;import io.tvos.settings.SettingsListRenderer.Marker;import static io.tvos.settings.SettingsScreen.*;import java.util.*;
public class BindingHarness {
 static class CloudPanel {boolean hasFocus(){return false;}}final CloudPanel icloudPanel=new CloudPanel();
 Object getContext(){return this;}boolean lightSettings(){return true;}
 static class Symbols {static float width(Object c,String name,float size){return size;}static void draw(Object context,Canvas c,String name,float x,float y,float size,int color){}}

 boolean running=true,reduced;int frames;boolean localFlag(String key){return reduced;}void postInvalidateDelayed(long d){check(d==80,"spinner frame delay");frames++;}
 int screen,focus,settingsGroup;final SettingsPicker picker=new SettingsPicker();float k=1;static final int CITY_ROW=4;
 static class Position {float value;}final Position mainScroll=new Position(),listScroll=new Position();final Position[] pop=new Position[12];{for(int i=0;i<12;i++)pop[i]=new Position();}
 final RectF rowRect=new RectF();final SettingsListWindow listWindow=new SettingsListWindow(10);final SettingsListRenderer.Row rowData=new SettingsListRenderer.Row();
 final List<Integer> buttonEditor=new ArrayList<>(Arrays.asList(0,1,2,3,4,5)),categoryEditor=new ArrayList<>(Arrays.asList(0,1,2,3,4));
 static class Dock {final List<String[]> apps=new ArrayList<>();int size(){return apps.size();}String[] get(int i){return apps.get(i);}int position(String p){return p.equals("app20")?1:-1;}}final Dock dockEditor=new Dock();
 final List<String> appPkgs=new ArrayList<>(),appLabels=new ArrayList<>();String[] labels,values;boolean free=true,vpn;int selectedService=2;
 static class Prefs {static boolean collection=true;static boolean homeCollection(){return collection;}static boolean home;static boolean homeContinue(){return home;}static String language(){return "en";}static boolean automatic;static boolean automaticRegion(){return automatic;}static boolean topButtonEnabled(int id){return id%2==0;}static int topButtonPosition(int id){return id+1;}static boolean categoryEnabled(int id){return id%2==0;}static int categoryPosition(int id){return id+1;}}
 static class R {static class string {static final int settings_off=1,settings_vpn=2,settings_built_in=3;}}static class AppStrings {static String get(int id){return id==1?"Off":id==2?"VPN":"Built-in";}}
 class Store {int getInt(String key,int d){return 2;}String getString(String key,String d){return "2";}}Store localPrefs(){return new Store();}static class MovieService {static final String KEY="service";static int index(String s){return Integer.parseInt(s);}}
 String[] menuLabels(){return labels;}int rowCount(){return labels.length;}String menuButtonLabel(int id){return "Button"+id;}String categoryLabel(int id){return "Category"+id;}boolean freeActive(){return free;}boolean vpnTopEnabled(){return vpn;}
 class Values {int selection(int screen){return 2;}String[] menu(int page,int count){return values;}String vpnApp(){return "VPN app";}String[] settings(){return new String[]{"V0","V1","V2","V3","V4"};}}final Values valueSource=new Values();
 String[] keyLabels(){return new String[]{"Key0","Key1"};}String[] keyValues(List<String> p,List<String> l){return new String[]{"App0","App1"};}String[] settingLabels(){return new String[]{"L0","L1","L2","L3","L4"};}int randomRow(){return 5;}int listSize(){return screen==SHELF_EDIT?dockEditor.size():picker.labels.size();}
 static class Ui {static float halfCap(Paint p){return 12;}}Paint rowLabel=new Paint();class ListRenderer {void beginShelf(Paint p){}}final ListRenderer listRenderer=new ListRenderer();
 static class Snap {final String label,value;final Kind kind;final Marker marker;final boolean disabled,fit,show,after;final float inset,top,right;
 Snap(SettingsListRenderer.Row r,RectF b){label=r.label;value=r.value;kind=r.kind;marker=r.marker;disabled=r.disabled;fit=r.fitValue;show=r.showValue;after=r.markerAfterValue;inset=r.valueInset;top=b.top;right=b.right;}}
 final List<Snap> rows=new ArrayList<>();void drawRow(Canvas c){rows.add(new Snap(rowData,rowRect));}
 void menu(int page,int count){screen=page;labels=new String[count];values=new String[count];for(int i=0;i<count;i++){labels[i]="Row"+i;values[i]="Value"+i;}rows.clear();}
 Snap row(String label){for(Snap s:rows)if(s.label.equals(label))return s;throw new AssertionError(label);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}static void near(float a,float b){check(Math.abs(a-b)<.02,a+" != "+b);}
''' + ''.join(block(source,s) for s in ['        private SettingsListRenderer.Row prepareRow(', '        private void setApprovedRowRect(', '        private void setScrollingRowRect(', '        private SettingsListRenderer.Layout rowLayout(', '        private boolean menuChevronVisible(', '        private void drawUpdateSpinner(', '        private void drawMenu(', '        private void drawChoice(', '        private void drawMenuButtonEditor(', '        private void drawOrderEditor(', '        private void drawCategoryEditor(', '        private void drawShelfEditor(', '        private void drawPicker(', '        private void drawList('])+'''
 public static void main(String[] args){BindingHarness v=new BindingHarness();Canvas c=new Canvas();
 for(float k:new float[]{2f/3,1,2}){
  v.k=k;v.menu(MAIN,10);v.focus=3;v.mainScroll.value=.5f;v.drawMenu(c);check(v.rows.size()==10&&v.rows.get(9).label.equals("Row3"),"menu binds focused row last");near(v.row("Row3").top,(189+3*84-42)*k);check(v.row("Row3").marker==Marker.CHEVRON&&v.row("Row3").inset==51,"menu arrow and inset");
  v.menu(ABOUT,8);v.focus=0;v.drawMenu(c);check(v.row("Row0").kind==Kind.NORMAL,"name actionable");near(v.row("Row0").right,1830*k);for(int i=1;i<8;i++){check(v.row("Row"+i).kind==Kind.DETAIL,"About detail binding");near(v.row("Row"+i).right,1824*k);}
  v.menu(CITY,2);Prefs.automatic=true;v.drawMenu(c);check(v.row("Row1").disabled&&v.row("Row1").fit&&v.row("Row1").marker==Marker.NONE,"automatic region disables manual row");Prefs.automatic=false;v.rows.clear();v.drawMenu(c);check(!v.row("Row1").disabled&&v.row("Row1").marker==Marker.CHEVRON&&v.row("Row1").inset==51,"manual region row restored");
  v.menu(UPDATES,2);v.updateProgress.active=true;v.drawMenu(c);check(v.row("Row0").marker==Marker.SPINNER&&v.row("Row0").inset==13&&v.row("Row1").marker==Marker.NONE,"only busy update action requests spinner");v.updateProgress.active=false;
  v.menu(LANGUAGE,2);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals("Row1")?Marker.PICKER_CHECK:Marker.NONE),"language uses the same checkmark policy as region picker");
  for(int page:new int[]{THEME_CHOICE,TEMPERATURE_CHOICE,WEATHER_CHOICE,DREAM_CHOICE,SLIDESHOW_STYLE}){v.menu(page,(page==WEATHER_CHOICE||page==DREAM_CHOICE)?4:3);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals("Row2")?Marker.PICKER_CHECK:Marker.NONE),"multi-option menu displays one check and no selected word");}
  v.menu(SLEEP_TIMER,7);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals("Row2")?Marker.PICKER_CHECK:Marker.NONE),"sleep timeout uses checkmark without Selected text");
  v.menu(MOVIE_SERVICE,5);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals("Row2")?Marker.PICKER_CHECK:Marker.NONE),"service check only for selected provider");
  for(boolean continued:new boolean[]{false,true}){Prefs.home=continued;v.menu(HOME_CARD_STYLE,2);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals(continued?"Row0":"Row1")?Marker.PICKER_CHECK:Marker.NONE),"Home mode uses same checkmark as language and hides selected word");}
  for(boolean collection:new boolean[]{false,true}){Prefs.collection=collection;v.menu(HOME_VIEW,2);v.drawMenu(c);for(Snap s:v.rows)check(!s.show&&s.kind==Kind.PICKER&&s.marker==(s.label.equals(collection?"Row0":"Row1")?Marker.PICKER_CHECK:Marker.NONE),"view picker checkmark");
  v.menu(HOME_SCREEN,3);v.drawMenu(c);check(v.row("Row1").disabled==collection&&v.row("Row2").disabled!=collection,"only applicable Home controls enabled");check(v.row(collection?"Row1":"Row2").marker==Marker.NONE,"disabled Home setting has no chevron");}
  v.screen=MENU_BUTTONS;v.vpn=false;v.rows.clear();v.drawMenuButtonEditor(c);Snap footer=v.row("VPN");check(footer.disabled&&footer.fit&&footer.inset==61&&footer.marker==Marker.NONE&&footer.after,"disabled VPN footer retains layout");v.vpn=true;v.rows.clear();v.drawMenuButtonEditor(c);check(v.row("VPN").marker==Marker.CHEVRON&&!v.row("VPN").disabled,"enabled VPN footer");
  v.screen=CATEGORY_EDIT;v.rows.clear();v.drawCategoryEditor(c);check(v.rows.size()==5&&v.row("Category0").value.equals("#1")&&v.row("Category1").value.equals("Off"),"category bindings retain order state");
  for(int selected=0;selected<=v.buttonEditor.size();selected++){
   v.screen=MENU_BUTTONS;v.focus=selected;v.rows.clear();v.drawMenuButtonEditor(c);
   check(v.rows.size()==7&&v.rows.get(6).label.equals(selected==6?"VPN":"Button"+selected),"shared button renderer paints focused row last, including VPN footer");
   for(Snap row:v.rows)if(!row.label.equals("VPN"))check(!row.disabled&&!row.fit&&row.inset==13&&row.marker==Marker.NONE,"VPN styling never leaks into a button");
   v.screen=CATEGORY_EDIT;v.focus=selected%5;v.rows.clear();v.drawCategoryEditor(c);
   check(v.rows.size()==5&&v.rows.get(4).label.equals("Category"+(selected%5)),"shared category renderer paints focused row last without footer");
   for(Snap row:v.rows)check(!row.disabled&&!row.fit&&row.inset==13&&row.marker==Marker.NONE&&!row.after,"switching from VPN clears footer-specific fields");
  }
  v.rows.clear();v.free=true;v.drawChoice(c);check(v.row("tvOS").marker==Marker.CHOICE_CHECK&&!v.row("tvOS").show,"launcher choice binding");v.rows.clear();v.free=false;v.drawChoice(c);check(v.row("Built-in").marker==Marker.CHOICE_CHECK,"original launcher selected");
 }
 int before=v.frames;v.drawUpdateSpinner(c,1,2,1,123);check(v.frames==before+1&&!v.updateProgress.reduced,"active spinner requests frame");v.running=false;v.drawUpdateSpinner(c,1,2,1,123);check(v.frames==before+1,"paused spinner does not request frames");v.running=true;v.reduced=true;v.drawUpdateSpinner(c,1,2,1,123);check(v.frames==before+1&&v.updateProgress.reduced,"Reduce Motion spinner remains static");
 for(int i=0;i<35;i++){v.dockEditor.apps.add(new String[]{"app"+i,"App"+i});v.picker.labels.add("Pick"+i);}
 v.screen=SHELF_EDIT;v.focus=v.listWindow.openPicker(20,35);v.listScroll.value=v.listWindow.offset()+.5f;v.rows.clear();v.drawShelfEditor(c);check(v.rows.size()>10&&v.rows.get(v.rows.size()-1).label.equals("App20")&&v.row("App20").value.equals("#2"),"Dock overscan and focus binding");for(Snap s:v.rows)check(s.kind==Kind.SHELF,"Dock text policy");
 v.screen=PICKER;v.picker.current=20;v.rows.clear();v.drawPicker(c);for(Snap s:v.rows)check(s.kind==Kind.PICKER&&!s.show&&s.marker==(s.label.equals("Pick20")?Marker.PICKER_CHECK:Marker.NONE),"picker overscan marks only selected item");
 v.screen=REMOTE_KEYS;v.focus=1;v.rows.clear();v.drawList(c);check(v.rows.get(1).label.equals("Key1"),"legacy keys focus last");for(Snap s:v.rows)check(s.marker==Marker.CHEVRON&&s.after&&s.inset==51,"legacy arrow drawn after value");
 v.screen=HOME_SETTINGS;v.settingsGroup=SYSTEM;v.focus=0;v.rows.clear();v.drawList(c);check(v.rows.size()==5&&v.row("L0").value.equals("V0")&&v.row("L0").marker==Marker.NONE,"legacy system row binding");
 System.out.println("PASS: all actual Activity row bindings, menu/About/region/service/launcher/VPN/editors/picker/legacy, active-last, overscan and scaled bounds");
 }
}'''
# Only this test double is used to determine whether the update row requests a spinner.
harness=harness.replace(' String[] menuLabels()', ' static class Progress {boolean active,reduced;boolean busy(){return active;}void drawSpinner(Canvas c,float x,float y,float k,int ink,boolean r){reduced=r;}}final Progress updateProgress=new Progress();\n String[] menuLabels()')
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);sources=geometry_sources();sources['io/tvos/BindingHarness.java']=harness;sources['io/tvos/settings/SettingsScreen.java']=Path('app/src/io/tvos/settings/SettingsScreen.java').read_text();sources['io/tvos/settings/SettingsListWindow.java']=Path('app/src/io/tvos/settings/SettingsListWindow.java').read_text()
 for name,body in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body)
 subprocess.run(['java','com.sun.tools.javac.Main','app/src/io/tvos/SettingsPicker.java','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.BindingHarness'],check=True)
