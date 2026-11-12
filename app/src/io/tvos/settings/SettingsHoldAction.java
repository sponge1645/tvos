package io.tvos.settings;

import android.os.Handler;
import android.view.KeyEvent;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** A scoped OK hold. No key repeats are required; releasing a hold never fires a short press. */
public final class SettingsHoldAction {
    private final Handler handler;private final BooleanSupplier eligible;private final Runnable tap;private final LongConsumer hold;
    private boolean down,fired;private int code,device;private long downTime;
    private final Runnable timeout=new Runnable(){@Override public void run(){if(down&&eligible.getAsBoolean()){fired=true;hold.accept(downTime);}}};
    public SettingsHoldAction(Handler handler,BooleanSupplier eligible,Runnable tap,LongConsumer hold){this.handler=handler;this.eligible=eligible;this.tap=tap;this.hold=hold;}
    public void cancel(){handler.removeCallbacks(timeout);down=false;fired=false;}
    public boolean onKey(KeyEvent event){
        int key=event.getKeyCode();boolean ok=key==23||key==66||key==160;
        if(!ok){if(event.getAction()==KeyEvent.ACTION_DOWN)cancel();return false;}
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()!=0)return down;
            if(!eligible.getAsBoolean())return false;
            cancel();down=true;code=key;device=event.getDeviceId();downTime=event.getDownTime();handler.postDelayed(timeout,500);return true;
        }
        if(event.getAction()!=KeyEvent.ACTION_UP||!down)return false;
        boolean same=key==code&&event.getDeviceId()==device;
        boolean click=same&&!fired&&!event.isCanceled()&&eligible.getAsBoolean();cancel();if(click)tap.run();return true;
    }
}
