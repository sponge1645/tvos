package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import io.tvos.settings.SettingsLogoRenderer;

/** Focus-owned QR form with a short logo/QR crossfade and no hidden listening socket. */
final class ICloudSetupPanel {
    private final Context context;
    private final Runnable wake;
    private ICloudSetup session;
    private Path qr;
    private int modules;
    private String address="",status="";
    private boolean shown;
    private float alpha;
    private long last,qrAt;
    private int generation;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ICloudSetupPanel(Context context,Runnable wake){this.context=context;this.wake=wake;}
    void pause(){generation++;if(session!=null)session.close();session=null;shown=false;alpha=0;last=0;qr=null;address="";}
    private void focus(boolean show) {
        if(shown==show)return;shown=show;generation++;int token=generation;
        if(session!=null)session.close();session=null;
        if(!show)return;
        qr=null;address="";status=AppStrings.get(R.string.icloud_connecting);
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
            if(qr!=null){int qrLayer=c.saveLayerAlpha(0,0,960*k,1080*k,Math.round(255*content));paint.setColor(0xFFFFFFFF);c.drawRoundRect(270*k,250*k,774*k,754*k,20*k,20*k,paint);int save=c.save();c.translate(286*k,266*k);c.scale(472*k/modules,472*k/modules);paint.setColor(0xFF000000);c.drawPath(qr,paint);c.restoreToCount(save);
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
