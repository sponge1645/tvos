"""Exercise pure settings routing and actual view activation/back effects without Android drawing."""
from settings_strings_fixture import write as write_strings
from settings_preferences_fixture import write as write_preferences
from pathlib import Path
import subprocess,tempfile
from settings_action_fixture import method,view,SOURCE
assert 'backFromScreen();' in SOURCE
harness='''package io.tvos;import java.util.*;import static io.tvos.settings.SettingsScreen.*;import io.tvos.settings.SettingsActions;
public class ActionsTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static ActionView create(int screen,int row){ActionView v=new ActionView();v.screen=screen;v.focus=row;return v;}
 public static void main(String[] args){
  check(SettingsActions.activation(WIDGETS,0,0,false,6,false)==SettingsActions.Action.WEATHER_SCREEN,"first widget opens weather");
  check(SettingsActions.activation(WIDGETS,1,0,false,6,false)==SettingsActions.Action.DATE_SCREEN,"second widget opens date");
  check(SettingsActions.activation(WIDGETS,2,0,false,6,false)==SettingsActions.Action.NONE,"removed third widget is inactive");
  int[] targets={GENERAL,MEDIA,SCREENSAVER,-1,-1,ACCESSIBILITY,APPS_DOWNLOADS,SHELF_EDIT,TOP_PANEL,SYSTEM};
  for(int row=0;row<10;row++){
   ActionView v=create(MAIN,row);v.activate();
   if(targets[row]>=0)check(v.screen==targets[row],"main row destination "+row);
   if(row==3)check(ActionView.events.equals(Arrays.asList("airplay")),"AirPlay returns without focus reset");
   if(row==4)check(ActionView.events.contains("remote-source-frame")&&!ActionView.events.contains("remote-service")&&!ActionView.events.contains("load-apps")&&ActionView.events.stream().anyMatch(e->e.startsWith("start:")),"remote screen does not enable accessibility");
  }
  ActionView sleep=create(MAIN,10);sleep.activate();check(ActionView.events.contains("sleep:-1"),"Sleep action sends immediate sleep");
  sleep=create(GENERAL,2);sleep.activate();check(sleep.screen==SLEEP_TIMER,"sleep timeout opens list below appearance");
  sleep.focus=4;sleep.activate();check(sleep.screen==GENERAL&&sleep.focus==2&&ActionView.events.contains("sleep:4"),"sleep choice applies and returns focus to timeout row");
  ActionView placement=create(WEATHER,0);placement.activate();check(placement.screen==WEATHER_CHOICE,"weather presentation opens list");placement.focus=3;placement.activate();check(placement.screen==WEATHER&&placement.focus==0&&placement.store.getInt("weather_placement",-1)==3,"explicit weather placement and return focus");
  ActionView source=create(SCREENSAVER,0);source.activate();check(source.screen==DREAM_CHOICE,"screensaver source opens list");source.focus=2;source.activate();check(source.screen==SCREENSAVER&&source.focus==0&&source.store.getString("video_dream_source","").equals("na"),"explicit screensaver source and return focus");
  ActionView style=create(SCREENSAVER,1);style.activate();check(style.screen==SCREENSAVER,"slideshow style disabled for non-iCloud source");style.store.putString("video_dream_source","icloud");style.activate();check(style.screen==SLIDESHOW_STYLE,"style enabled for iCloud source");style.focus=2;style.activate();check(style.screen==SCREENSAVER&&style.focus==1&&style.store.getString("icloud_style","").equals("slide"),"style persists and restores focus");
  ActionView disconnect=create(SCREENSAVER,6);disconnect.activate();check(!ActionView.events.contains("disconnect-icloud"),"unconfigured album cannot be disconnected");disconnect.store.putString("icloud_album","configured");disconnect.activate();check(ActionView.events.contains("disconnect-icloud")&&disconnect.focus==6,"disconnect action preserves focus");
  int[][] back={{HOME_SCREEN,TOP_PANEL,0},{HOME_VIEW,HOME_SCREEN,0},{HOME_CARD_STYLE,HOME_SCREEN,1},{GENERAL,MAIN,0},{MEDIA,MAIN,1},{MOVIE_SERVICE,MEDIA,0},{SCREENSAVER,MAIN,2},{REMOTE_KEYS,MAIN,4},{ACCESSIBILITY,MAIN,5},{APPS_DOWNLOADS,MAIN,6},{SHELF_EDIT,MAIN,7},{TOP_PANEL,MAIN,8},{WIDGETS,TOP_PANEL,2},{SYSTEM,MAIN,9},{CITY,WEATHER,1},{LANGUAGE,GENERAL,3},{SLEEP_TIMER,GENERAL,2},{THEME_CHOICE,GENERAL,1},{TEMPERATURE_CHOICE,WEATHER,2},{WEATHER_CHOICE,WEATHER,0},{DREAM_CHOICE,SCREENSAVER,0},{SLIDESHOW_STYLE,SCREENSAVER,1},{ABOUT,GENERAL,0},{UPDATES,SYSTEM,0},{RESET,SYSTEM,1},{WEATHER,TOP_PANEL,3},{DATE_TIME,TOP_PANEL,2},{HOME_CHOICE,SYSTEM,2},{MENU_BUTTONS,TOP_PANEL,1},{CATEGORY_EDIT,HOME_SCREEN,2}};
  for(int[] route:back){check(io.tvos.settings.SettingsScreen.parent(route[0],GENERAL,0)==route[1],"animation parent agrees with Back destination");ActionView v=create(route[0],0);v.backFromScreen();check(v.appLoader.cancelled&&v.tvProviderLoader.cancelled,"Back cancels both pending app lookups");check(v.screen==route[1]&&v.focus==route[2],"Back destination "+route[0]);}
  for(int count=0;count<=6;count++){
   ActionView v=create(PICKER,0);v.picker.kind=PICK_VPN;v.buttonCount=count;v.rebuildMenuButtonRows(-1);v.backFromScreen();
   check(v.screen==MENU_BUTTONS&&v.focus==count,"VPN picker returns directly to Buttons footer");
   check(!SettingsActions.canAdjust(MENU_BUTTONS,count,count),"VPN footer is not arrow-adjustable");
  }
  for(int group:new int[]{-1,MAIN,GENERAL,SYSTEM,TOP_PANEL}){
   int expected=group==SYSTEM?TOP_PANEL:group;
   check(io.tvos.settings.SettingsScreen.parent(HOME_SETTINGS,group,0)==expected,"legacy settings parent");
   SettingsActions.Target target=SettingsActions.back(HOME_SETTINGS,0,group,6);check(target.screen==expected&&target.row==0,"legacy Back keeps group and row");
  }
  for(int picker:new int[]{PICK_CITY,PICK_VPN,1,2,3,4,9}){
   int expected=picker==PICK_CITY?CITY:picker==PICK_VPN?MENU_BUTTONS:REMOTE_KEYS;
   int row=picker==PICK_CITY?1:picker==PICK_VPN?6:picker-1;
   check(io.tvos.settings.SettingsScreen.parent(PICKER,GENERAL,picker)==expected,"picker animation parent");
   SettingsActions.Target target=SettingsActions.back(PICKER,picker,GENERAL,6);check(target.screen==expected&&target.row==row,"picker Back focus");
  }
  check(io.tvos.settings.SettingsScreen.parent(MAIN,GENERAL,0)==-1&&io.tvos.settings.SettingsScreen.parent(999,GENERAL,0)==-1,"root and unknown have no animation parent");
  check(SettingsActions.back(999,0,GENERAL,6).screen==MAIN&&SettingsActions.back(999,0,GENERAL,6).row==0,"unknown Back fallback retained");
  ActionView language=create(GENERAL,3);language.activate();check(language.screen==LANGUAGE,"language opens list");language.focus=0;language.activate();check(language.screen==GENERAL&&language.focus==3&&ActionView.Prefs.language.equals("ru"),"language selection saves and returns");
  ActionView country=create(GENERAL,4);country.activate();check(country.screen==PICKER&&country.picker.kind==PICK_REGION,"region opens country list");country.focus=0;country.activate();check(country.screen==GENERAL&&country.focus==4&&ActionView.events.contains("country:FR"),"country saves ISO code");
  ActionView provider=create(MEDIA,2);provider.activate();check(provider.screen==PICKER&&provider.picker.kind==PICK_TV,"TV provider opens target list");provider.focus=1;provider.activate();check(provider.screen==MEDIA&&provider.focus==2&&ActionView.events.contains("tv:"),"empty target unselects provider");
  ActionView service=create(MEDIA,0);service.activate();check(service.screen==MOVIE_SERVICE,"service row opens choices");
  service=create(MOVIE_SERVICE,3);service.activate();check(service.screen==MEDIA&&service.focus==0&&ActionView.events.contains("movie_service:seena"),"service selection saves stable identity and returns to parent");service.activate();check(service.screen==MOVIE_SERVICE&&service.focus==3,"reopening focuses the saved service");
  ActionView v=create(PICKER,2);v.picker.kind=PICK_CITY;v.activate();
  check(v.screen==CITY&&v.focus==1&&ActionView.events.get(0).startsWith("city:"),"city selected before returning to General");
  v=create(PICKER,1);v.picker.kind=PICK_VPN;v.activate();
  check(v.screen==MENU_BUTTONS&&v.focus==v.buttonEditor.size()&&ActionView.Prefs.shelf.equals(Arrays.asList("watch.kino")),"VPN selection preserves removal of duplicate INCY Dock entry");
  v=create(PICKER,1);v.picker.kind=2;v.activate();check(v.screen==REMOTE_KEYS&&v.focus==1&&ActionView.events.contains("key:youtube:app1"),"remote picker updates correct key");
  v=create(HOME_CHOICE,0);v.busyFlag=true;v.activate();check(ActionView.events.equals(Arrays.asList("invalidate"))&&v.modeMonitor.pendingAt==0,"busy home choice cannot restart request");
  v=create(HOME_CHOICE,1);v.activate();check(v.screen==HOME_CHOICE&&v.modeMonitor.pendingAt==123&&ActionView.events.contains("home:2"),"launcher selection stays on same screen");
  v=create(SCREENSAVER,5);v.activate();check(ActionView.events.equals(Arrays.asList("pause","preview")),"preview pauses view before opening");
  v=create(GENERAL,1);v.activate();check(v.screen==THEME_CHOICE&&!ActionView.events.contains("theme:light"),"appearance opens list without changing preference");v.focus=1;v.activate();check(v.screen==GENERAL&&v.focus==1&&ActionView.events.contains("theme:light"),"explicit appearance choice applies and restores focus");
  v=create(GENERAL,0);v.activate();check(v.screen==ABOUT&&ActionView.events.contains("snap"),"page change resets focus springs");
  for(int[] route:new int[][]{{TOP_PANEL,1,MENU_BUTTONS},{HOME_SCREEN,2,CATEGORY_EDIT},{MAIN,7,SHELF_EDIT}}){
   v=create(route[0],route[1]);v.activate();check(v.screen==route[2],"editor opens expected page");
   check(ActionView.events.contains("snap")&&ActionView.events.contains("invalidate")&&ActionView.events.contains("spoken"),"editor starts transition immediately without waiting for another input or background event");
  }
  v=create(SYSTEM,2);v.activate();check(v.screen==HOME_CHOICE,"System launcher row opens choice");
  check(SettingsActions.activation(ACCESSIBILITY,2,0,false,6,false)==SettingsActions.Action.TV_SETTINGS,"TV settings remains third accessibility row");
  v=create(RESET,0);v.activate();check(ActionView.events.equals(Arrays.asList("confirm-cache")),"cache clear requires existing confirmation");
  v=create(RESET,1);v.activate();check(ActionView.events.equals(Arrays.asList("confirm-reset")),"reset requires existing confirmation");
  v=create(REMOTE_KEYS,REMOTE_INPUT_ROW);v.activate();check(ActionView.events.contains("input-method")&&!ActionView.events.contains("load-apps"),"input method setup opens without package picker");
  check(!SettingsActions.canAdjust(REMOTE_KEYS,REMOTE_INPUT_ROW,6),"input method setup is an OK-only action");
  check(!SettingsActions.canAdjust(REMOTE_KEYS,4,6)&&SettingsActions.activation(REMOTE_KEYS,4,0,false,6,false)==SettingsActions.Action.KEY_PICKER,"MI still uses app picker");
  check(SettingsActions.activation(UPDATES,2,0,false,6,false)==SettingsActions.Action.NONE,"retired test row has no action");
  v=create(UPDATES,0);v.activate();check(v.updateRequested&&ActionView.events.indexOf("update:false")<ActionView.events.indexOf("on-update"),"update check order preserved");
  v=create(SYSTEM,3);v.activate();check(ActionView.events.contains("start:HomeActivity:3")&&ActionView.events.get(ActionView.events.size()-1).equals("finish"),"restart targets HomeActivity then closes Settings");
  v=create(MENU_BUTTONS,6);v.vpnFlag=false;v.activate();check(!ActionView.events.contains("load-apps"),"hidden VPN app cannot open picker");
  v=create(MENU_BUTTONS,6);v.vpnFlag=true;v.activate();check(v.screen==PICKER&&v.picker.kind==PICK_VPN,"enabled VPN opens package picker");
  v=create(CITY,0);v.activate();check(ActionView.Prefs.auto,"auto region toggles on");v.focus=1;v.activate();check(!ActionView.events.contains("ask-city"),"manual city disabled in auto mode");v.focus=0;v.activate();v.focus=1;v.activate();check(ActionView.events.contains("ask-city"),"manual city enabled when auto is off");
  v=create(WEATHER,1);v.activate();check(v.screen==CITY,"Weather opens City submenu");v=create(TOP_PANEL,2);v.activate();check(ActionView.events.contains("date-toggle"),"date toggles directly in Home settings");
  v=create(WEATHER,2);v.activate();check(v.screen==TEMPERATURE_CHOICE,"temperature opens choice list");v.focus=1;v.activate();check(v.screen==WEATHER&&v.focus==2&&ActionView.events.contains("unit:C"),"temperature applies exact selected unit");
  v=create(GENERAL,5);v.activate();check(ActionView.events.contains("hour24:true"),"time format follows removed date row");
  v=create(MEDIA,1);v.activate();check(ActionView.events.contains("flag:navigation_clicks:false"),"click toggle follows removed date row");
  v=create(ACCESSIBILITY,2);v.activate();check(ActionView.events.contains("tv-settings"),"system settings uses safe launcher helper");
  ActionView.Prefs.collection=true;v=create(TOP_PANEL,0);v.activate();check(v.screen==HOME_SCREEN,"Home container belongs to Control Center");
  v.focus=1;v.activate();check(v.screen==HOME_SCREEN,"card choices disabled in collection");
  v.focus=2;v.activate();check(v.screen==CATEGORY_EDIT,"collection editor available");
  v=create(HOME_SCREEN,0);v.activate();check(v.screen==HOME_VIEW&&v.focus==0,"view picker focuses collection");
  v.focus=1;v.activate();check(!ActionView.Prefs.collection&&v.screen==HOME_SCREEN&&v.focus==0,"tvOS view saves and returns");
  v.focus=2;v.activate();check(v.screen==HOME_SCREEN,"collection editor disabled for tvOS");
  v.focus=1;v.activate();check(v.screen==HOME_CARD_STYLE,"tvOS opens card style");v.focus=0;v.activate();check(ActionView.Prefs.home&&v.screen==HOME_SCREEN&&v.focus==1,"continue cards save and return");v.activate();check(v.screen==HOME_CARD_STYLE&&v.focus==0,"saved card style receives focus");v.focus=1;v.activate();check(!ActionView.Prefs.home,"recommendations restore");
  v.focus=0;v.activate();v.focus=0;v.activate();check(ActionView.Prefs.collection,"collection can be restored");

  check(SettingsActions.back(MAIN,0,GENERAL,6)==null,"Back from root remains activity-owned");
  System.out.println("PASS: settings action policy, activation effects/order, Back targets, live VPN footer, focus reset, launcher/preview/update/reset behavior");
 }
}
'''
# Public wrappers let the harness call the exact private production bodies.
production=view('ActionView').replace('private void activate()', 'void activate()').replace('private void backFromScreen()', 'void backFromScreen()')
with tempfile.TemporaryDirectory(prefix='tvos-settings-actions-') as tmp:
 root=Path(tmp);(root/'ActionView.java').write_text(production);(root/'ActionsTest.java').write_text(harness)
 for name in ('SettingsContent','SettingsActions','SettingsListWindow'):(root/(name+'.java')).write_text(Path('app/src/io/tvos/'+('settings/' if name in ('SettingsActions','SettingsListWindow') else '')+name+'.java').read_text())
 write_strings(root)
 write_preferences(root,"ActionView.Prefs")
 subprocess.run(['java','com.sun.tools.javac.Main','app/src/io/tvos/SettingsPicker.java','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.ActionsTest'],check=True)
