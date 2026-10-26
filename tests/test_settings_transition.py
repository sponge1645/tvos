"""Exercise production menu focus timing, rapid navigation, reduced motion and update timestamps."""
from settings_strings_fixture import write as write_strings
from pathlib import Path
import re,subprocess,tempfile
source=Path('app/src/io/tvos/SettingsActivity.java').read_text()
def method(sig):
 a=source.index(sig);b=source.index('{',a);i=b+1;d=1
 while d:
  d+=(source[i]=='{')-(source[i]=='}');i+=1
 return source[a:i]
constants=re.search(r'static final int (MAIN\s*=.*?);',Path('app/src/io/tvos/SettingsContent.java').read_text()).group(1)
header='''package io.tvos;
public class SettingsTransitionHarness {
 static final int '''+constants+''';
 static final int PICK_CITY=0,PICK_VPN=-1;static final long MENU_TRANSITION_MS=280;
 static class Ui {static boolean ru;static java.util.Locale locale(){return ru?new java.util.Locale("ru","RU"):java.util.Locale.US;}}
 String tr(String r,String e){return Ui.ru?r:e;}
 int screen=MAIN,focus,drawnScreen=-1,transitionFrom=-1,updatePopup,settingsGroup=GENERAL,pickFor;
 long transitionStarted,focusReadyAt;boolean reduceMotion;
 Spring[] pop={new Spring(.28f,.85f,0),new Spring(.28f,.85f,0),new Spring(.28f,.85f,0),new Spring(.28f,.85f,0),new Spring(.28f,.85f,0),new Spring(.28f,.85f,0)};
 boolean localFlag(String key){return reduceMotion;}int rowCount(){return screen==ABOUT?8:10;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
'''
tests='''
 public static void main(String[] args)throws Exception{
  SettingsTransitionHarness h=new SettingsTransitionHarness();h.syncMenuTransition(0);h.stepRowFocus(1f/60,0);
  check(h.pop[0].value>0 && h.pop[0].value<1,"initial focus appears softly");
  h.pop[0].snap(1);h.screen=GENERAL;h.focus=1;h.syncMenuTransition(100);
  check(h.transitionFrom==MAIN && h.focusReadyAt==380,"focus waits for page transition");
  for(Spring p:h.pop)check(p.value==0 && p.target==0,"old focus never carries to new page");
  h.stepRowFocus(.016f,379);check(h.pop[1].value==0,"no focus during page movement");
  h.stepRowFocus(.016f,380);check(h.pop[1].value>0 && h.pop[1].value<1,"focus springs in only after transition");
  h.screen=ABOUT;h.focus=0;h.syncMenuTransition(390);h.stepRowFocus(.016f,669);
  check(h.pop[0].value==0 && h.pop[1].value==0,"rapid navigation resets both inherited focus and deadline");
  h.stepRowFocus(.016f,670);check(h.pop[0].value>0,"latest page receives focus after its own transition");
  h.focus=3;h.stepRowFocus(.016f,700);check(h.pop[3].target==0,"read-only About rows stay unhighlighted");
  h.screen=GENERAL;h.focus=2;h.reduceMotion=true;h.syncMenuTransition(800);h.stepRowFocus(.016f,800);
  check(h.transitionFrom==-1 && h.pop[2].value==1,"Reduce Motion skips page delay and focus animation");
  Ui.ru=false;AppLanguage.select(Ui.ru?"ru":"en");check(h.keyLabels()[0].equals("Netflix Button"),"remote buttons capitalized in English");
  Ui.ru=true;AppLanguage.select(Ui.ru?"ru":"en");check(h.keyLabels()[0].equals("Кнопка Netflix"),"capitalized keys keep Russian translation");
  h.screen=ABOUT;Ui.ru=false;AppLanguage.select(Ui.ru?"ru":"en");check(h.screenTitle().equals("About"),"About heading matches menu");
  h.screen=SCREENSAVER;check(h.screenTitle().equals("Screen Saver"),"Screen Saver heading matches menu");
  h.screen=HOME_CHOICE;check(h.screenTitle().equals("Default Launcher"),"launcher heading matches accessibility entry");
  java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
  long at=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-10-24 07:18:10").getTime();
  check(formatLastUpdateCheck(at,AppLanguage.ENGLISH).equals("Last check: October 24, 2026 at 07:18:10"),"English requested date and time format");
  check(formatLastUpdateCheck(at,AppLanguage.RUSSIAN).equals("Последняя проверка: 24 октября 2026 г. в 07:18:10"),"Russian localized date and time");
  check(formatLastUpdateCheck(0,AppLanguage.ENGLISH).equals("Last check: Never"),"never checked is explicit");
  check(!formatLastUpdateCheck(at+1000,AppLanguage.ENGLISH).equals(formatLastUpdateCheck(at,AppLanguage.ENGLISH)),"timestamp comes from data, not example text");
  java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+03:00"));
  check(formatLastUpdateCheck(at,AppLanguage.ENGLISH).endsWith("10:18:10"),"TV timezone used");
  System.out.println("PASS: delayed soft focus, inherited selection reset, rapid navigation, Reduce Motion, real localized last check");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp);p=root/'SettingsTransitionHarness.java';p.write_text(header+''.join(method(s) for s in ['        private void syncMenuTransition(', '        private void stepRowFocus(', '        private int parentScreen(', '        private String[] keyLabels()', '        private String screenTitle()', '        private static String formatLastUpdateCheck('])+tests)
 content=root/'SettingsContent.java';content.write_text(Path('app/src/io/tvos/SettingsContent.java').read_text())
 spring=root/'Spring.java';spring.write_text(Path('app/src/io/tvos/Spring.java').read_text())
 write_strings(root)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.SettingsTransitionHarness'],check=True)
