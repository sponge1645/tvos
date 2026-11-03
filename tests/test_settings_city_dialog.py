"""Execute the shared Settings/setup city input and its platform/IME submission paths."""
from pathlib import Path
import subprocess,tempfile
sources={
'android/content/Context.java':'package android.content;public class Context {}',
'android/R.java':'package android;public class R {public static class style {public static final int Theme_DeviceDefault_Dialog_Alert=1;}}',
'android/view/inputmethod/EditorInfo.java':'package android.view.inputmethod;public class EditorInfo {public static final int IME_ACTION_SEARCH=3;}',
'android/widget/EditText.java':'''package android.widget;public class EditText {public static EditText last;public String value="",hint;public int action;public boolean single;public Listener listener;public interface Listener {boolean run(EditText field,int action,Object event);}public EditText(android.content.Context c){last=this;}public void setSingleLine(boolean b){single=b;}public void setHint(String s){hint=s;}public void setImeOptions(int a){action=a;}public String getText(){return value;}public void setOnEditorActionListener(Listener l){listener=l;}}''',
'android/app/AlertDialog.java':'''package android.app;public class AlertDialog {public boolean showing;public Click positive,negative;public interface Click {void run(AlertDialog dialog,int which);}public void show(){showing=true;}public void dismiss(){showing=false;}public static class Builder {final AlertDialog dialog=new AlertDialog();public Builder(android.content.Context c,int theme){if(theme!=1)throw new AssertionError();}public Builder setTitle(String s){return this;}public Builder setView(android.widget.EditText field){return this;}public Builder setPositiveButton(String text,Click click){dialog.positive=click;return this;}public Builder setNegativeButton(String text,Click click){dialog.negative=click;return this;}public AlertDialog create(){return dialog;}}}''',
'io/tvos/AppStrings.java':'package io.tvos;class AppStrings {static String get(int id){return "text"+id;}}',
'io/tvos/R.java':'package io.tvos;class R {static class string {static final int settings_city_name_hint=1,settings_city=2,settings_find_city=3,settings_cancel=4;}}',
'io/tvos/CityDialogTest.java':'''package io.tvos;import android.app.AlertDialog;import android.widget.EditText;import java.util.*;class CityDialogTest {static void check(boolean b,String s){if(!b)throw new AssertionError(s);}public static void main(String[] args){List<String> queries=new ArrayList<>();AlertDialog button=SettingsCityDialog.show(new android.content.Context(),queries::add);check(button.showing&&EditText.last.single&&EditText.last.action==3,"same native themed single-line Search field");EditText.last.value="Chisinau";button.positive.run(button,1);check(queries.equals(Arrays.asList("Chisinau")),"Find submits actual field text");AlertDialog ime=SettingsCityDialog.show(new android.content.Context(),queries::add);EditText.last.value="Balti";check(EditText.last.listener.run(EditText.last,3,null)&&!ime.showing&&queries.equals(Arrays.asList("Chisinau","Balti")),"keyboard Search dismisses then submits");AlertDialog canceled=SettingsCityDialog.show(new android.content.Context(),queries::add);canceled.dismiss();check(queries.size()==2&&canceled.negative==null,"Cancel never submits a city");System.out.println("PASS: actual shared city input, native theme, Search IME/button and optional cancel");}}'''
}
sources['io/tvos/SettingsCityDialog.java']=Path('app/src/io/tvos/SettingsCityDialog.java').read_text()
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,text in sources.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.CityDialogTest'],check=True,timeout=15)
