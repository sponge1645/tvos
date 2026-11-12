package io.tvos.settings;

/** Two sequential 140ms phases: old tile fades out, then the new tile fades in at its original size. */
public final class SettingsTileMotion {
    private SettingsTileMotion() { }
    private static float ease(float x){x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);}
    public static float out(long elapsed){return 1-ease(elapsed/140f);}
    public static float in(long elapsed){return ease((elapsed-140)/140f);}
}
