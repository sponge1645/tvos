package io.tvos;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import io.tvos.settings.SettingsBackground;
import io.tvos.settings.SettingsListRenderer;
import io.tvos.settings.SettingsLogoRenderer;
import io.tvos.settings.SettingsRowRenderer;
import io.tvos.settings.SettingsTextRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The same settings canvas, typography and capsules, with app artwork and a focused delete control. */
public final class StorageActivity extends Activity {
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private Menu menu;
    private int generation;
    private volatile int iconGeneration;
    private volatile boolean closed;
    private boolean loading,deleting,loadFailed;
    private List<StorageApps.Item> items=new ArrayList<>();
    private String selected="";
    private android.content.Context sizeContext;
    private final java.util.Map<String,String> sizes=new java.util.HashMap<>();
    private Bitmap artwork,previousArtwork;
    private long artworkAt;
    private RemoteShortcuts shortcuts;
    private String text(int id){return AppStrings.get(id);}
    @Override protected void onCreate(Bundle state){super.onCreate(state);Ui.init(this);android.content.res.Configuration config=new android.content.res.Configuration(getResources().getConfiguration());config.setLocale(AppLanguage.current().locale);sizeContext=createConfigurationContext(config);menu=new Menu();setContentView(menu);shortcuts=new RemoteShortcuts(this,true,null);}
    @Override protected void onResume(){super.onResume();if(shortcuts!=null)shortcuts.reload();load();}
    @Override protected void onPause(){if(menu!=null&&!deleting){menu.confirm=false;menu.confirmFocus=0;}if(shortcuts!=null)shortcuts.cancel();super.onPause();}
    @Override protected void onDestroy(){closed=true;generation++;iconGeneration++;work.shutdownNow();if(shortcuts!=null)shortcuts.close();menu.clear();artwork=previousArtwork=null;super.onDestroy();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(!focus&&shortcuts!=null)shortcuts.cancel();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(shortcuts!=null&&shortcuts.onKey(event))return true;
        if(menu.key(event))return true;
        return super.dispatchKeyEvent(event);
    }
    private void load(){
        if(closed)return;loading=true;final int token=++generation;menu.invalidate();
        work.execute(()->{
            List<StorageApps.Item> result;boolean failed=false;
            try{result=StorageApps.load(this);}catch(RuntimeException e){result=new ArrayList<>();failed=true;AppLog.w("Storage list unavailable");}
            final boolean error=failed;final List<StorageApps.Item> ready=result;
            runOnUiThread(()->{if(closed||token!=generation)return;items=ready;sizes.clear();loading=false;loadFailed=error;
                int previous=menu.focus;for(int n=0;n<items.size();n++)if(items.get(n).pkg.equals(selected)){previous=n;break;}
                menu.focus=Math.max(0,Math.min(previous,items.size()-1));menu.reveal();select();menu.invalidate();});
        });
    }
    private void select(){
        if(items.isEmpty()){selected="";artwork=null;return;}
        StorageApps.Item item=items.get(menu.focus);selected=item.pkg;
        menu.setContentDescription(item.label+", "+size(item)+", "+text(item.removable?R.string.storage_delete:R.string.storage_protected));
        final int token=++iconGeneration;final String pkg=item.pkg;
        work.execute(()->{
            if(closed||token!=iconGeneration)return;
            Bitmap bitmap=null;
            try {
                ApplicationInfo app=getPackageManager().getApplicationInfo(pkg,0);
                Drawable drawable=app.loadBanner(getPackageManager());if(drawable==null)drawable=app.loadIcon(getPackageManager());
                int iw=Math.max(1,drawable.getIntrinsicWidth()),ih=Math.max(1,drawable.getIntrinsicHeight());
                float scale=512f/Math.max(iw,ih);int width=Math.max(1,Math.round(iw*scale)),height=Math.max(1,Math.round(ih*scale));
                bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,width,height);drawable.draw(new Canvas(bitmap));
            }catch(Exception ignored){}
            final Bitmap ready=bitmap;
            runOnUiThread(()->{if(closed||token!=iconGeneration)return;previousArtwork=artwork;artwork=ready;artworkAt=SystemClock.uptimeMillis();menu.invalidate();});
        });
    }
    private String size(StorageApps.Item item){String value=sizes.get(item.pkg);if(value==null){value=item.bytes<0?"—":Formatter.formatShortFileSize(sizeContext,item.bytes);sizes.put(item.pkg,value);}return value;}
    private void delete(){
        if(deleting||items.isEmpty())return;
        StorageApps.Item item=items.get(menu.focus);if(!item.removable)return;
        deleting=true;menu.invalidate();
        work.execute(()->{boolean ok=StorageApps.uninstall(this,item.pkg);runOnUiThread(()->{
            if(closed)return;deleting=false;menu.confirm=false;menu.confirmFocus=0;
            if(ok)load();else{Toast.makeText(this,text(R.string.storage_delete_failed),Toast.LENGTH_LONG).show();menu.invalidate();}
        });});
    }
    private final class Menu extends View {
        final SettingsBackground background=new SettingsBackground();
        final SettingsLogoRenderer logo=new SettingsLogoRenderer();
        final SettingsTextRenderer typography=new SettingsTextRenderer();
        final Shadows shadows=new Shadows();
        final SettingsRowRenderer capsules=new SettingsRowRenderer(shadows::draw);
        final SettingsListRenderer renderer=new SettingsListRenderer(capsules,typography,new SettingsListRenderer.Check(){
            @Override public float width(float size){return 0;}
            @Override public void draw(Canvas c,float x,float y,float size,int ink){}
        },(c,x,y,scale,ink)->{});
        final SettingsListRenderer.Row row=new SettingsListRenderer.Row();
        final Paint title=new Paint(3),value=new Paint(3),ink=new Paint(3),bitmapPaint=new Paint(3);
        final android.text.TextPaint label=new android.text.TextPaint(3);
        final RectF bounds=new RectF(),trash=new RectF(),image=new RectF();final Path clip=new Path();
        final Spring focusSpring=new Spring(.3f,1,0);
        int focus,offset,confirmFocus;boolean confirm;float k=1;long last;int pressed=-1;
        Menu(){super(StorageActivity.this);setFocusable(true);setFocusableInTouchMode(true);requestFocus();}
        void clear(){background.clear();typography.clear();shadows.clear();}
        void reveal(){offset=Math.max(0,Math.min(offset,focus));if(focus>=offset+9)offset=focus-8;}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){k=w/1920f;clear();}
        void activate(){
            if(loadFailed){load();return;}
            if(loading||deleting||items.isEmpty())return;
            if(confirm){if(confirmFocus==1)delete();else{confirm=false;invalidate();}return;}
            if(!items.get(focus).removable)return;
            confirm=true;confirmFocus=0;focusSpring.snap(0);invalidate();
        }
        boolean key(KeyEvent event){
            int code=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
            if(code==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled()&&!deleting){if(confirm){confirm=false;invalidate();}else finish();}return true;}
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())activate();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN){
                if(down&&!loading&&!deleting){int direction=code==KeyEvent.KEYCODE_DPAD_UP?-1:1;
                    if(confirm)confirmFocus=Math.max(0,Math.min(1,confirmFocus+direction));
                    else if(!items.isEmpty()){int next=Math.max(0,Math.min(items.size()-1,focus+direction));if(next!=focus){focus=next;reveal();select();}}
                    focusSpring.snap(0);invalidate();}
                return true;
            }
            return code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_DOWN){pressed=(int)((event.getY()/k-189)/84)+offset;return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){int hit=(int)((event.getY()/k-189)/84)+offset;
                if(hit==pressed&&event.getX()>1000*k&&!loading&&!deleting){if(confirm){if(hit-offset>=0&&hit-offset<2){confirmFocus=hit-offset;activate();}}
                    else if(hit>=0&&hit<items.size()){focus=hit;reveal();select();if(event.getX()>1734*k)activate();invalidate();}}
                return true;}
            return true;
        }
        @Override protected void onDraw(Canvas c){
            long now=SystemClock.uptimeMillis();float dt=Math.min(.05f,Math.max(0,now-last)/1000f);last=now;
            boolean light=!HomeData.get(getContext()).isNight(System.currentTimeMillis());
            boolean reduced=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("reduce_motion",false);
            background.draw(c,getWidth(),getHeight(),light);
            title.setTypeface(Ui.displayBold);title.setTextSize(56*k);title.setTextAlign(Paint.Align.CENTER);title.setColor(light?0xFF738190:0xFF5A676D);
            typography.drawTracked(c,text(R.string.storage_title),getWidth()/2f,111*k,title,.005f);
            label.setTypeface(Ui.displaySemibold);label.setTextSize(36*k);value.setTypeface(Ui.displayRegular);value.setTextSize(36*k);value.setTextAlign(Paint.Align.RIGHT);
            float alpha=reduced?1:Math.min(1,(now-artworkAt)/220f);
            if(artwork==null)logo.draw(c,k,light,Ui.displaySemibold);
            else {
                if(alpha<1){if(previousArtwork==null)logo.draw(c,k,light,Ui.displaySemibold);else drawArtwork(c,previousArtwork,1);}
                drawArtwork(c,artwork,alpha);if(alpha<1)postInvalidateOnAnimation();else previousArtwork=null;
            }
            focusSpring.target=1;if(reduced)focusSpring.snap(1);else if(focusSpring.step(dt))postInvalidateOnAnimation();
            String hint=text(R.string.storage_hint);
            if(loading||items.isEmpty()){
                label.setColor(SettingsListRenderer.normalInk(light));c.drawText(text(loading?R.string.remote_loading:loadFailed?R.string.storage_load_failed:R.string.storage_empty),1080*k,230*k,label);
            }else if(confirm){
                StorageApps.Item app=items.get(focus);hint=String.format(AppLanguage.current().locale,text(R.string.storage_confirm),app.label);
                for(int n=0;n<2;n++){SettingsListRenderer.approvedBounds(bounds,n,k);row.set(text(n==0?R.string.settings_cancel:R.string.storage_delete),"",!deleting&&n==confirmFocus?focusSpring.value:0);row.disabled=deleting;
                    renderer.draw(c,bounds,k,bounds.centerY()+Ui.halfCap(label),light,label,value,row);}
                if(deleting)hint=text(R.string.storage_deleting);
            }else{
                label.setTextSize(28*k);label.setColor(SettingsListRenderer.secondaryInk(light));c.drawText(text(R.string.storage_apps),1080*k,164*k,label);label.setTextSize(36*k);
                for(int n=offset;n<Math.min(items.size(),offset+9);n++){
                    StorageApps.Item app=items.get(n);SettingsListRenderer.approvedBounds(bounds,n-offset,k);bounds.right-=100*k;
                    String shown=TextUtils.ellipsize(app.label,label,420*k,TextUtils.TruncateAt.END).toString();
                    row.set(shown,size(app),0);row.fitValue=true;renderer.draw(c,bounds,k,bounds.centerY()+Ui.halfCap(label),light,label,value,row);
                    float amount=n==focus?focusSpring.value:0;trash.set(1748*k,bounds.top-5*k,1846*k,bounds.bottom+5*k);
                    ink.setStyle(Paint.Style.FILL);ink.setColor(SettingsListRenderer.blend(light?0xFFC9E4F7:0xFF31444B,0xFFF5F5F7,amount));c.drawRoundRect(trash,trash.height()/2,trash.height()/2,ink);
                    drawTrash(c,trash.centerX(),trash.centerY(),app.removable?(amount>.5f?0xFF1C1C1E:SettingsListRenderer.normalInk(light)):0x66808080);
                }
                StorageApps.Item app=items.get(focus);if(!app.removable)hint=text(R.string.storage_protected);else if(app.bytes<0)hint=text(R.string.storage_size_unavailable);
            }
            typography.drawDescription(c,hint,k,light,Ui.displaySemibold);
        }
        void drawArtwork(Canvas c,Bitmap bitmap,float alpha){
            float width=536*k,height=width*bitmap.getHeight()/bitmap.getWidth();if(height>536*k){height=536*k;width=height*bitmap.getWidth()/bitmap.getHeight();}
            image.set(522*k-width/2,504*k-height/2,522*k+width/2,504*k+height/2);
            clip.reset();clip.addRoundRect(image,32*k,32*k,Path.Direction.CW);c.save();c.clipPath(clip);
            bitmapPaint.setAlpha((int)(255*alpha));c.drawBitmap(bitmap,null,image,bitmapPaint);c.restore();
        }
        void drawTrash(Canvas c,float x,float y,int color){
            ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(3*k);ink.setStrokeCap(Paint.Cap.ROUND);ink.setColor(color);
            c.drawLine(x-16*k,y-12*k,x+16*k,y-12*k,ink);c.drawRoundRect(x-12*k,y-10*k,x+12*k,y+18*k,3*k,3*k,ink);
            c.drawLine(x-6*k,y-18*k,x+6*k,y-18*k,ink);c.drawLine(x-6*k,y-18*k,x-6*k,y-12*k,ink);c.drawLine(x+6*k,y-18*k,x+6*k,y-12*k,ink);
            c.drawLine(x-4*k,y-4*k,x-4*k,y+10*k,ink);c.drawLine(x+4*k,y-4*k,x+4*k,y+10*k,ink);ink.setStyle(Paint.Style.FILL);
        }
    }
}
