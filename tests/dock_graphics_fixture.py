"""Deterministic graphics fixture for production Dock/branding JVM regressions."""
GRAPHICS = {
    'android/graphics/Bitmap.java': '''package android.graphics;public class Bitmap {
        public enum Config {ARGB_8888};public static int scales;public boolean recycled;public int w,h,top,bottom;public String asset;
        public float logoWidth,logoHeight;public Bitmap(int w,int h){this.w=w;this.h=h;}
        public int getWidth(){return w;}public int getHeight(){return h;}public void setHasMipMap(boolean value){}
        public void recycle(){recycled=true;}public static Bitmap createBitmap(int w,int h,Config config){return new Bitmap(w,h);}
        public static Bitmap createScaledBitmap(Bitmap source,int w,int h,boolean filter){if(source.w==w&&source.h==h)return source;
            scales++;Bitmap out=new Bitmap(w,h);out.asset=source.asset;return out;}}''',
    'android/graphics/RectF.java': '''package android.graphics;public class RectF {
        public float left,top,right,bottom;public RectF(){}public RectF(float l,float t,float r,float b){set(l,t,r,b);}
        public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
        public float width(){return right-left;}public float height(){return bottom-top;}
        public float centerX(){return(left+right)/2;}public float centerY(){return(top+bottom)/2;}}''',
    'android/graphics/Shader.java': 'package android.graphics;public class Shader {public enum TileMode {CLAMP}}',
    'android/graphics/LinearGradient.java': '''package android.graphics;public class LinearGradient extends Shader {
        public int top,bottom;public LinearGradient(float x,float y,float xx,float yy,int t,int b,TileMode mode){top=t;bottom=b;}}''',
    'android/graphics/PorterDuff.java': 'package android.graphics;public class PorterDuff {public enum Mode{SRC_IN}}',
    'android/graphics/PorterDuffColorFilter.java': 'package android.graphics;public class PorterDuffColorFilter {public PorterDuffColorFilter(int c,PorterDuff.Mode m){}}',
    'android/graphics/Paint.java': '''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;
        public Object filter;public Shader shader;public Paint(int flags){}public void setColorFilter(Object f){filter=f;}public void setShader(Shader s){shader=s;}}''',
    'android/graphics/Canvas.java': '''package android.graphics;public class Canvas {
        public float l,t,r,b;public Bitmap image,target;public Object filter;public Paint paint;
        public Canvas(){}public Canvas(Bitmap target){this.target=target;}
        public void drawBitmap(Bitmap image,Object src,RectF dst,Paint paint){this.image=image;this.paint=paint;filter=paint.filter;
            l=dst.left;t=dst.top;r=dst.right;b=dst.bottom;if(target!=null){target.asset=image.asset;target.logoWidth=r-l;target.logoHeight=b-t;}}
        public void drawRect(float l,float t,float r,float b,Paint p){if(target!=null&&p.shader instanceof LinearGradient){
            LinearGradient g=(LinearGradient)p.shader;target.top=g.top;target.bottom=g.bottom;}}}''',
}
