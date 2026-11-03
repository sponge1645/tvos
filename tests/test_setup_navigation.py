"""Exercise the real Activity navigation barrier in both directions and after pause."""
from pathlib import Path
import subprocess,tempfile
source=Path('app/src/io/tvos/SetupActivity.java').read_text()
a=source.index('    private void navigate(');b=source.index('    @SuppressWarnings',a)
method=source[a:b]+source[source.index('    private void skip()'):source.index('    private void navigate(')]
code='''package io.tvos;import io.tvos.setup.SetupState;import android.content.Intent;import java.util.function.Consumer;
class NavigationTest {int page,transitionEpoch,renders;boolean permissionFlow,pendingPermissions;boolean active=true,transitioning;Consumer<Runnable> leaveScene;final Store state=new Store();static class Store {int step;Store edit(){return this;}Store putBoolean(String key,boolean v){return this;}Store remove(String key){return this;}Store putInt(String key,int value){step=value;return this;}void apply(){}}Store setup(){return state;}static class HomeActivity {}static class SettingsActivity {}static class HomeDefault {static boolean selected(Object activity){return false;}}void startActivity(Intent i){}void finish(){}void cancelWork(){}void render(){renders++;transitioning=false;transitionEpoch++;}
'''+method+'''
static void check(boolean b,String m){if(!b)throw new AssertionError(m);}public static void main(String[] args){for(int[] pair:new int[][]{{0,1},{1,2},{2,1},{1,0},{2,3},{3,7},{7,5},{5,6},{6,5},{5,7},{7,3},{3,2}}){NavigationTest n=new NavigationTest();n.page=pair[0];Runnable[] exit={null};n.leaveScene=action->exit[0]=action;n.navigate(pair[1]);check(n.page==pair[0]&&n.renders==0&&n.transitioning,"outgoing scene remains until fade/blank finishes");n.navigate(6);check(n.page==pair[0],"repeated input cannot skip the fade barrier");exit[0].run();check(n.page==pair[1]&&n.state.step==pair[1]&&n.renders==1,"both directions persist and render only after background");}
NavigationTest paused=new NavigationTest();Runnable[] pending={null};paused.leaveScene=action->pending[0]=action;paused.navigate(1);paused.active=false;paused.transitionEpoch++;pending[0].run();check(paused.page==0&&paused.renders==0,"paused outgoing callback is stale");
NavigationTest last=new NavigationTest();last.page=6;Runnable[] complete={null};last.leaveScene=action->complete[0]=action;last.skip();check(last.transitioning&&complete[0]!=null,"final Home screen also fades before completion");
NavigationTest inactive=new NavigationTest();inactive.active=false;inactive.page=3;inactive.navigate(7);check(inactive.page==7&&inactive.state.step==7&&inactive.renders==0,"permission result before resume saves Weather for resumed rendering");System.out.println("PASS: real navigation fade barrier, symmetric intro transitions, duplicate/stale guards and deferred permission resume");}}
'''
with tempfile.TemporaryDirectory() as tmp:
 root=Path(tmp)
 for name,text in {'android/content/Intent.java':'package android.content;public class Intent {public Intent(Object activity,Class<?> target){}}','io/tvos/NavigationTest.java':code,'io/tvos/setup/SetupState.java':Path('app/src/io/tvos/setup/SetupState.java').read_text()}.items():
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-Xlint:all,-options','-Werror','-d',tmp,*map(str,root.rglob('*.java'))],check=True)
 subprocess.run(['java','-cp',tmp,'io.tvos.NavigationTest'],check=True,timeout=15)
