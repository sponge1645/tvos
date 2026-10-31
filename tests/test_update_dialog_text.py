"""Run the production bounded wrapper on words, blank lines and supplementary Unicode."""
from pathlib import Path
import tempfile,subprocess
harness='''package io.tvos;import io.tvos.settings.UpdateDialogText;import java.util.*;public class TextHarness{
static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
public static void main(String[] args){
java.util.function.ToDoubleFunction<String> measure=s->s.codePointCount(0,s.length())*10;
String[] lines=UpdateDialogText.wrap(new String[]{"one two three","","four\\nfive"},80,20,measure);check(Arrays.equals(lines,new String[]{"one two","three","","four","five"}),"paragraphs, words and explicit lines");
String unicode=String.join("",Collections.nCopies(100,"😃"));lines=UpdateDialogText.wrap(new String[]{unicode},55,30,measure);check(String.join("",lines).equals(unicode),"unbroken supplementary text keeps every code point");for(String line:lines){check(measure.applyAsDouble(line)<=55,"unbroken line stays bounded");for(int i=0;i<line.length();i++){if(Character.isHighSurrogate(line.charAt(i)))check(++i<line.length()&&Character.isLowSurrogate(line.charAt(i)),"no split surrogate");}}
lines=UpdateDialogText.wrap(new String[]{unicode},55,3,measure);check(lines.length==3&&lines[2].endsWith("…")&&measure.applyAsDouble(lines[2])<=55,"bounded height uses fitting ellipsis");
lines=UpdateDialogText.wrap(new String[]{"a","","",""},30,2,measure);check(lines.length==2&&lines[1].endsWith("…"),"blank lines obey limit");
System.out.println("PASS: actual update text wrapping, words/paragraphs, unbroken Unicode, code point boundaries and bounded ellipsis");}}
'''
with tempfile.TemporaryDirectory(prefix='tvos-update-text-') as tmp:
 root=Path(tmp)
 for name,text in [('UpdateDialogText.java',Path('app/src/io/tvos/settings/UpdateDialogText.java').read_text()),('TextHarness.java',harness)]: (root/name).write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','-d',tmp,*map(str,root.glob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.TextHarness'],check=True)
