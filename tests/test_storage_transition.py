"""Execute the actual animated-page painter: only parent rows are repainted during a slide."""
from pathlib import Path
import subprocess,tempfile
from tmdb_metadata_fixture import block
source=Path('app/src/io/tvos/StorageSettings.java').read_text()
method=block(source,'        void drawTransitionPage(')
harness='''class Canvas{}class Test{int parent,child;java.util.function.Consumer<Canvas> sourceRows=c->parent++;void drawRows(Canvas c){child++;}
'''+method+'''
public static void main(String[] args){Test t=new Test();Canvas c=new Canvas();t.drawTransitionPage(c,0);if(t.parent!=1||t.child!=0)throw new AssertionError("parent slide must paint rows only");t.drawTransitionPage(c,1);if(t.parent!=1||t.child!=1)throw new AssertionError("child slide must paint storage rows only");System.out.println("PASS: actual storage slide painter uses lightweight parent rows and stable child rows");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'Test.java';p.write_text(harness)
 subprocess.run(['java','com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:all,-options','-Werror','-d',tmp,str(p)],check=True)
 subprocess.run(['java','-cp',tmp,'Test'],check=True)
assert 'transitions.focusReady(now)&&!exiting' in source
assert 'sourceBackground.accept(c)' in source and 'new SettingsBackground' not in source
assert source.index('if(!rowsReady){sourceView.draw(c)') < source.index('if(!started){started=true;entered=now;')
