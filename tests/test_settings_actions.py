"""Exercise pure settings routing and actual view activation/back effects without Android drawing."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import subprocess,tempfile
from settings_action_fixture import method,view,SOURCE
assert 'backFromScreen();' in SOURCE
harness='''package io.tvos;import java.util.*;import static io.tvos.SettingsContent.*;
public class ActionsTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static ActionView create(int screen,int row){ActionView v=new ActionView();v.screen=screen;v.focus=row;return v;}
 public static void main(String[] args){
  check(SettingsActions.activation(WIDGETS,0,0,false,6,false)==SettingsActions.Action.WEATHER_SCREEN,"first widget opens weather");
  check(SettingsActions.activation(WIDGETS,1,0,false,6,false)==SettingsActions.Action.DATE_SCREEN,"second widget opens date");
  check(SettingsActions.activation(WIDGETS,2,0,false,6,false)==SettingsActions.Action.NONE,"removed third widget is inactive");
  int[] targets={GENERAL,SCREENSAVER,-1,REMOTE_KEYS,ACCESSIBILITY,APPS_DOWNLOADS,SHELF_EDIT,TOP_PANEL,WIDGETS,SYSTEM};
  for(int row=0;row<10;row++){
   ActionView v=create(MAIN,row);v.activate();
   if(targets[row]>=0)check(v.screen==targets[row],"main row destination "+row);
   if(row==2)check(ActionView.events.equals(Arrays.asList("airplay")),"AirPlay returns without focus reset");
   if(row==3)check(ActionView.events.indexOf("remote-service")<ActionView.events.indexOf("load-apps"),"remote permission setup precedes app load");
  }
  int[][] back={{GENERAL,MAIN,0},{SCREENSAVER,MAIN,1},{REMOTE_KEYS,MAIN,3},{ACCESSIBILITY,MAIN,4},{APPS_DOWNLOADS,MAIN,5},{SHELF_EDIT,MAIN,6},{TOP_PANEL,MAIN,7},{WIDGETS,MAIN,8},{SYSTEM,MAIN,9},{ABOUT,GENERAL,0},{UPDATES,SYSTEM,0},{RESET,SYSTEM,1},{WEATHER,WIDGETS,0},{DATE_TIME,WIDGETS,1},{HOME_CHOICE,SYSTEM,2},{MENU_BUTTONS,TOP_PANEL,1},{CATEGORY_EDIT,TOP_PANEL,0}};
  for(int[] route:back){ActionView v=create(route[0],0);v.backFromScreen();check(v.screen==route[1]&&v.focus==route[2],"Back destination "+route[0]);}
  for(int count=0;count<=6;count++){
   ActionView v=create(VPN_SETTINGS,1);v.buttonCount=count;v.backFromScreen();
   check(v.screen==MENU_BUTTONS&&v.focus==count&&ActionView.events.get(0).equals("rebuild-buttons"),"Back rebuilds live buttons before selecting footer");
   check(!SettingsActions.canAdjust(MENU_BUTTONS,count,count),"VPN footer is not arrow-adjustable");
  }
  ActionView v=create(PICKER,2);v.pickFor=PICK_CITY;v.activate();
  check(v.screen==GENERAL&&v.focus==3&&ActionView.events.get(0).startsWith("city:"),"city selected before returning to General");
  v=create(PICKER,1);v.pickFor=PICK_VPN;v.activate();
  check(v.screen==VPN_SETTINGS&&v.focus==1&&ActionView.Prefs.shelf.equals(Arrays.asList("watch.kino")),"VPN selection preserves removal of duplicate INCY Dock entry");
  v=create(PICKER,1);v.pickFor=2;v.activate();check(v.screen==REMOTE_KEYS&&v.focus==1&&ActionView.events.contains("key:youtube:app1"),"remote picker updates correct key");
  v=create(HOME_CHOICE,0);v.busyFlag=true;v.activate();check(ActionView.events.equals(Arrays.asList("invalidate"))&&v.pendingAt==0,"busy home choice cannot restart request");
  v=create(HOME_CHOICE,1);v.activate();check(v.screen==HOME_CHOICE&&v.pendingAt==123&&ActionView.events.contains("home:2"),"launcher selection stays on same screen");
  v=create(SCREENSAVER,4);v.activate();check(ActionView.events.equals(Arrays.asList("pause","preview")),"preview pauses view before opening");
  v=create(GENERAL,1);v.activate();check(!ActionView.events.contains("snap")&&ActionView.events.contains("theme:light"),"value change preserves current focus animation");
  v=create(GENERAL,0);v.activate();check(v.screen==ABOUT&&ActionView.events.contains("snap"),"page change resets focus springs");
  v=create(SYSTEM,2);v.activate();check(v.screen==HOME_CHOICE,"System launcher row opens choice");
  check(SettingsActions.activation(ACCESSIBILITY,2,0,false,6,false)==SettingsActions.Action.TV_SETTINGS,"TV settings remains third accessibility row");
  v=create(RESET,0);v.activate();check(ActionView.events.equals(Arrays.asList("confirm-cache")),"cache clear requires existing confirmation");
  v=create(RESET,1);v.activate();check(ActionView.events.equals(Arrays.asList("confirm-reset")),"reset requires existing confirmation");
  v=create(UPDATES,0);v.activate();check(v.updateRequested&&ActionView.events.indexOf("update:false")<ActionView.events.indexOf("on-update"),"update check order preserved");
  v=create(SYSTEM,3);v.activate();check(ActionView.events.contains("start:HomeActivity:3")&&ActionView.events.get(ActionView.events.size()-1).equals("finish"),"restart targets HomeActivity then closes Settings");
  v=create(VPN_SETTINGS,1);v.vpnFlag=false;v.activate();check(!ActionView.events.contains("load-apps"),"hidden VPN app cannot open picker");
  v=create(VPN_SETTINGS,1);v.vpnFlag=true;v.activate();check(v.screen==PICKER&&v.pickFor==PICK_VPN,"enabled VPN opens package picker");
  check(SettingsActions.back(MAIN,0,GENERAL,6)==null,"Back from root remains activity-owned");
  System.out.println("PASS: settings action policy, activation effects/order, Back targets, live VPN footer, focus reset, launcher/preview/update/reset behavior");
 }
}
'''
# Public wrappers let the harness call the exact private production bodies.
production=view('ActionView').replace('private void activate()', 'void activate()').replace('private void backFromScreen()', 'void backFromScreen()')
with tempfile.TemporaryDirectory(prefix='tvos-settings-actions-') as tmp:
 root=Path(tmp);(root/'ActionView.java').write_text(production);(root/'ActionsTest.java').write_text(harness)
 for name in ('SettingsContent','SettingsActions'):(root/(name+'.java')).write_text(Path('app/src/io/tvos/'+name+'.java').read_text())
 write_strings(root)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ActionsTest'],check=True)
