package io.tvos;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.tvos.settings.SettingsBackground;
import io.tvos.setup.SetupArtwork;
import io.tvos.settings.SettingsRowRenderer;
import io.tvos.settings.SettingsListRenderer;

/** Two-action TV/Home screen: presentation and input only; Android selection stays in Activity. */
final class SetupFinishView extends View {
 private final SettingsBackground background=new SettingsBackground();
 private final Shadows shadows=new Shadows();
 private final SettingsRowRenderer buttons=new SettingsRowRenderer(shadows::draw);
 private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG),ink=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final RectF rect=new RectF();
 private final Path picture;
 private final Spring[] focus={new Spring(.28f,.85f,1),new Spring(.28f,.85f,0)};
 private final Runnable first,second;
 private final boolean television,reduced;
 private final String title,description;
 private final String[] choices;
 private int selected;
 private boolean pressed,leaving,completed;
 private long frame=SystemClock.uptimeMillis();
 SetupFinishView(Context c,boolean television,String app,Runnable first,Runnable second) {
  super(c);this.television=television;this.first=first;this.second=second;
  title=AppStrings.get(television?R.string.settings_setup_tv_title:R.string.settings_setup_home_title);
  description=AppStrings.get(television?R.string.settings_setup_tv_description:R.string.settings_setup_home_description);
  choices=new String[]{television?app:AppStrings.get(R.string.settings_setup_set_home),AppStrings.get(television?R.string.settings_setup_continue:R.string.settings_setup_not_now)};
  reduced=c.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("reduce_motion",false);
  picture=television?SetupArtwork.provider():SetupArtwork.sync_apps();
  setFocusable(true);setFocusableInTouchMode(true);describe();setOnClickListener(v->confirm());
 }
 void application(String value){choices[0]=value;describe();invalidate();}
 private void describe(){setContentDescription(title+". "+description+", "+choices[selected]);}
 private void confirm(){if(completed||(leaving&&selected==0))return;if(selected==0){if(!television)leaving=true;first.run();}else{completed=true;leaving=true;second.run();}}
 @Override protected void onDraw(Canvas c){
  super.onDraw(c);boolean light=!HomeData.get(getContext()).isNight(System.currentTimeMillis());background.draw(c,getWidth(),getHeight(),light);
  float k=Math.min(getWidth()/1920f,getHeight()/1080f);int saved=c.save();c.translate((getWidth()-1920*k)/2,(getHeight()-1080*k)/2);c.scale(k,k);
  int color=light?0xFF6E7E94:0xFF95B3BE;text.setColor(color);ink.setColor(color);text.setTextAlign(Paint.Align.CENTER);text.setTypeface(Ui.displayBold);
  String[] heading=title.split("\n");text.setTextSize(80);for(int i=0;i<heading.length;i++)fit(c,heading[i],620,340+i*94,860,80);
  text.setTypeface(Ui.displaySemibold);String[] lines=description.split("\n");for(int i=0;i<lines.length;i++)fit(c,lines[i],620,490+i*48,860,36);
  long now=SystemClock.uptimeMillis();float dt=Math.min(.05f,Math.max(0,(now-frame)/1000f));frame=now;boolean moving=false;
  for(int i=0;i<2;i++){focus[i].target=i==selected?1:0;if(reduced)focus[i].snap(focus[i].target);else moving|=focus[i].step(dt);float f=Math.max(0,Math.min(1,focus[i].value)),cy=700+i*105;
   if(f>.001f){rect.set(191.7778f,cy-30,974,cy+30);buttons.drawFocused(c,rect,1,light,f);}text.setColor(SettingsListRenderer.blend(color,0xFF1C1C1E,f));fit(c,choices[i],620,cy+14,730,40);
  }
  int mark=c.save();c.translate(1480,605);float s=television?.55f:.46f;c.scale(s,s);c.translate(-700,-700);c.drawPath(picture,ink);c.restoreToCount(mark);c.restoreToCount(saved);
  if(moving&&hasWindowFocus())postInvalidateOnAnimation();
 }
 private void fit(Canvas c,String value,float x,float y,float width,float size){text.setTextSize(size);float w=text.measureText(value);if(w>width)text.setTextSize(size*width/w);c.drawText(value,x,y,text);}
 @Override public void onWindowFocusChanged(boolean f){super.onWindowFocusChanged(f);pressed=false;leaving=false;if(f)invalidate();}
 @Override protected void onDetachedFromWindow(){background.clear();shadows.clear();super.onDetachedFromWindow();}
 @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){int next=key==KeyEvent.KEYCODE_DPAD_UP?0:1;if(next!=selected){focus[selected].snap(0);selected=next;}describe();invalidate();return true;}if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){if(e.getRepeatCount()==0)pressed=true;return true;}return super.onKeyDown(key,e);}
 @Override public boolean onKeyUp(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)return true;if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){boolean go=pressed&&!e.isCanceled();pressed=false;if(go)confirm();return true;}return super.onKeyUp(key,e);}
}
