package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.KeyEvent;
import io.tvos.settings.SettingsLogoRenderer;

/** Focus-owned QR form with a short logo/QR crossfade and no hidden listening socket. */
final class ICloudSetupPanel {
    private final Context context;
    private final Runnable wake;
    private ICloudSetup session;
    private Path qr;
    private int modules;
    private String address="",status="";
    private boolean shown,buttonFocused,okDown;
    private final java.util.function.Consumer<String> announce;
    private float alpha;
    private long last,qrAt;
    private int generation;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ICloudSetupPanel(Context context,Runnable wake){this(context,wake,text -> {});}
    ICloudSetupPanel(Context context,Runnable wake,java.util.function.Consumer<String> announce){this.context=context;this.wake=wake;this.announce=announce;}
    private boolean linked(){return !context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("icloud_album","").isEmpty();}
    boolean hasFocus(){return shown&&buttonFocused&&linked();}
    boolean onKey(KeyEvent event,boolean visible) {
        if(!visible){buttonFocused=okDown=false;return false;}
        if(!linked())return false;
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(!down&&!event.isCanceled()){buttonFocused=true;announce.accept(AppStrings.get(R.string.icloud_disconnect));wake.run();}return true;}
        if(!buttonFocused)return false;
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_BACK){if(!down){buttonFocused=okDown=false;announce.accept(AppStrings.get(R.string.icloud_photos));wake.run();}return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)return true;
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){
            if(down){if(event.getRepeatCount()==0)okDown=true;}
            else {boolean apply=okDown&&!event.isCanceled();okDown=false;if(apply){ICloudSetup.disconnect(context);shown=false;buttonFocused=false;focus(true);wake.run();}}
            return true;
        }
        return false;
    }
    void pause(){generation++;if(session!=null)session.close();session=null;shown=buttonFocused=okDown=false;alpha=0;last=0;qr=null;address="";}
    private void focus(boolean show) {
        if(shown==show)return;shown=show;generation++;int token=generation;
        if(session!=null)session.close();session=null;
        if(!show){buttonFocused=okDown=false;return;}
        qr=null;address="";status=AppStrings.get(R.string.icloud_connecting);
        if(linked()){status=AppStrings.get(R.string.icloud_saved);return;}
        session=new ICloudSetup(context,new ICloudSetup.Listener(){
            public void ready(String value,Path path,int size){if(token!=generation)return;address=value;qr=path;modules=size;qrAt=SystemClock.uptimeMillis();status="";wake.run();}
            public void message(int resource){if(token!=generation)return;qr=null;status=AppStrings.get(resource);wake.run();}
            public void saved(){if(token!=generation)return;qr=null;address="";status=AppStrings.get(R.string.icloud_saved);wake.run();}
        });
    }
    void draw(Canvas c,float k,boolean light,boolean show,boolean reduceMotion,SettingsLogoRenderer logo) {
        focus(show);long now=SystemClock.uptimeMillis();float dt=last==0?0:Math.min(64,now-last);last=now;
        float target=show?1:0;alpha=reduceMotion?target:alpha<target?Math.min(target,alpha+dt/240f):Math.max(target,alpha-dt/240f);
        if(alpha<.999f){int layer=c.saveLayerAlpha(0,0,960*k,1080*k,Math.round(255*(1-alpha)));logo.draw(c,k,light,Ui.displaySemibold);c.restoreToCount(layer);}
        if(alpha>.001f){int layer=c.saveLayerAlpha(0,0,960*k,1080*k,Math.round(255*alpha));
            float content=reduceMotion?1:Math.max(0,Math.min(1,(now-qrAt)/240f));
            boolean connected=linked();
            if(connected){
                paint.setColor(buttonFocused?0xFFFFFFFF:light?0x338897AC:0x33777777);c.drawRoundRect(285*k,550*k,759*k,624*k,18*k,18*k,paint);
                paint.setTypeface(Ui.displaySemibold);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(30*k);paint.setColor(buttonFocused?0xFF1C1C1E:light?0xFF34444C:0xFFF2F5FA);
                c.drawText(AppStrings.get(R.string.icloud_disconnect),522*k,598*k,paint);
                paint.setTextSize(24*k);c.drawText(buttonFocused?"OK":AppStrings.get(R.string.icloud_disconnect_hint),522*k,679*k,paint);
            }
            if(!connected&&qr!=null){int qrLayer=c.saveLayerAlpha(0,0,960*k,1080*k,Math.round(255*content));paint.setColor(0xFFFFFFFF);c.drawRoundRect(270*k,250*k,774*k,754*k,20*k,20*k,paint);int save=c.save();c.translate(286*k,266*k);c.scale(472*k/modules,472*k/modules);paint.setColor(0xFF000000);c.drawPath(qr,paint);c.restoreToCount(save);
                paint.setTypeface(Ui.displaySemibold);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(27*k);paint.setColor(light?0xFF34444C:0xFFF2F5FA);
                if(!address.isEmpty())c.drawText(address,522*k,803*k,paint);
                c.restoreToCount(qrLayer);if(show&&content<1)wake.run();
            }
            paint.setTypeface(Ui.displaySemibold);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(27*k);paint.setColor(light?0xFF34444C:0xFFF2F5FA);
            if(!status.isEmpty()){paint.setTextSize(25*k);c.drawText(status,522*k,qr==null?510*k:850*k,paint);}
            c.restoreToCount(layer);
        }
        if(alpha!=target)wake.run();
    }
}
