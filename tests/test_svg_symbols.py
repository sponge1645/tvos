"""Render real SVG loader/Canvas code on a Java2D fixture; verify all assets and caching."""
from pathlib import Path
import shutil
import subprocess
import tempfile

sources = {
    'org/xmlpull/v1/XmlPullParser.java': '''package org.xmlpull.v1;import java.io.*;
        public interface XmlPullParser {int END_DOCUMENT=1,START_TAG=2,END_TAG=3,TEXT=4;
        void setInput(InputStream in,String encoding)throws Exception;int next()throws Exception;
        String getName();String getAttributeValue(String namespace,String name); }''',
    'android/util/Xml.java': '''package android.util;import org.xmlpull.v1.*;import javax.xml.stream.*;import java.io.*;
        public class Xml {public static XmlPullParser newPullParser(){return new Parser();}
        static class Parser implements XmlPullParser {XMLStreamReader reader;
        public void setInput(InputStream in,String encoding)throws Exception{reader=XMLInputFactory.newFactory().createXMLStreamReader(in,encoding);}
        public int next()throws Exception{while(reader.hasNext()){int type=reader.next();
            if(type==XMLStreamConstants.START_ELEMENT)return START_TAG;if(type==XMLStreamConstants.END_ELEMENT)return END_TAG;
            if(type==XMLStreamConstants.END_DOCUMENT)return END_DOCUMENT;return TEXT;}return END_DOCUMENT;}
        public String getName(){return reader.getLocalName();}public String getAttributeValue(String ns,String key){return reader.getAttributeValue(ns,key);}}}''',
    'android/content/res/AssetManager.java': '''package android.content.res;import java.io.*;import java.util.*;
        public class AssetManager {public final File root;public Map<String,Integer> opens=new HashMap<>();
        public AssetManager(File root){this.root=root;}public synchronized InputStream open(String name)throws IOException{
            opens.put(name,opens.getOrDefault(name,0)+1);return new FileInputStream(new File(root,name));}
        public int count(){return opens.values().stream().mapToInt(i->i).sum();}}''',
    'android/content/Context.java': '''package android.content;import java.io.*;import android.content.res.*;
        public class Context {final AssetManager assets;public Context(File f){assets=new AssetManager(f);}public AssetManager getAssets(){return assets;}}''',
    'android/graphics/Path.java': '''package android.graphics;import java.awt.geom.*;
        public class Path {public enum FillType {EVEN_ODD,WINDING};public final Path2D.Float shape=new Path2D.Float();
        public void setFillType(FillType t){shape.setWindingRule(t==FillType.EVEN_ODD?Path2D.WIND_EVEN_ODD:Path2D.WIND_NON_ZERO);}
        public void moveTo(float x,float y){shape.moveTo(x,y);}public void lineTo(float x,float y){shape.lineTo(x,y);}
        public void cubicTo(float a,float b,float c,float d,float x,float y){shape.curveTo(a,b,c,d,x,y);}public void close(){shape.closePath();}}''',
    'android/graphics/Paint.java': '''package android.graphics;public class Paint {
        public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public int color=0xff000000;
        public Paint(int flags){}public void setColor(int c){color=c;}public void setAlpha(int a){color=(color&0xffffff)|(a<<24);}}''',
    'android/graphics/RectF.java': '''package android.graphics;public class RectF {public float left,top,right,bottom;
        public RectF(){}public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}}''',
    'android/graphics/Bitmap.java': '''package android.graphics;import java.awt.image.*;
        public class Bitmap {public enum Config{ALPHA_8,ARGB_8888};public static int allocations;public BufferedImage image;
        public Bitmap(BufferedImage image){this.image=image;}public static Bitmap createBitmap(int w,int h,Config config){allocations++;return new Bitmap(new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB));}
        public int getWidth(){return image.getWidth();}public int getHeight(){return image.getHeight();}public void setHasMipMap(boolean b){}
        public Bitmap extractAlpha(){BufferedImage alpha=new BufferedImage(getWidth(),getHeight(),BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<getHeight();y++)for(int x=0;x<getWidth();x++)alpha.setRGB(x,y,(image.getRGB(x,y)&0xff000000)|0xffffff);return new Bitmap(alpha);}}''',
    'android/graphics/BitmapFactory.java': '''package android.graphics;import java.io.*;import javax.imageio.*;
        public class BitmapFactory {public static int decodes;public static Bitmap decodeStream(InputStream in){decodes++;try{return new Bitmap(ImageIO.read(in));}catch(IOException e){return null;}}}''',
    'android/graphics/Canvas.java': '''package android.graphics;import java.awt.*;import java.awt.geom.*;import java.awt.image.*;import java.util.*;
        public class Canvas {
            public BufferedImage image;private Graphics2D graphics;private ArrayDeque<State> stack=new ArrayDeque<>();
            public int paths,layers;static class State{Graphics2D graphics;BufferedImage image;int alpha;boolean layer;
                State(Graphics2D g,BufferedImage i,int a,boolean l){graphics=g;image=i;alpha=a;layer=l;}}
            public Canvas(int w,int h){image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);graphics=image.createGraphics();hints();}
            public Canvas(Bitmap b){image=b.image;graphics=image.createGraphics();hints();}
            private void hints(){graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);}
            public int save(){stack.push(new State(graphics,image,255,false));graphics=(Graphics2D)graphics.create();return stack.size();}
            public int saveLayerAlpha(float l,float t,float r,float b,int a){layers++;stack.push(new State(graphics,image,a,true));
                AffineTransform transform=graphics.getTransform();image=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                graphics=image.createGraphics();hints();graphics.setTransform(transform);return stack.size();}
            public void restoreToCount(int count){while(stack.size()>=count){State state=stack.pop();graphics.dispose();
                if(state.layer){Graphics2D g=(Graphics2D)state.graphics.create();g.setTransform(new AffineTransform());
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,state.alpha/255f));g.drawImage(image,0,0,null);g.dispose();}
                graphics=state.graphics;image=state.image;}}
            public int depth(){return stack.size();}public void translate(float x,float y){graphics.translate(x,y);}public void scale(float x,float y){graphics.scale(x,y);}
            public void drawPath(Path p,Paint paint){paths++;graphics.setColor(new Color(paint.color,true));graphics.fill(p.shape);}
            public void drawBitmap(Bitmap b,Object source,RectF rect,Paint paint){
                BufferedImage tinted=new BufferedImage(b.getWidth(),b.getHeight(),BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++){
                    int alpha=Math.round((b.image.getRGB(x,y)>>>24)*(paint.color>>>24)/255f);
                    tinted.setRGB(x,y,(paint.color&0xffffff)|(alpha<<24));}
                AffineTransform tx=AffineTransform.getTranslateInstance(rect.left,rect.top);
                tx.scale((rect.right-rect.left)/b.getWidth(),(rect.bottom-rect.top)/b.getHeight());graphics.drawImage(tinted,tx,null);}
        }''',
    'io/tvos/SvgHarness.java': '''package io.tvos;
        import android.content.*;import android.graphics.*;import java.io.*;import java.nio.charset.*;import java.util.*;import javax.imageio.*;import java.awt.image.*;
        public class SvgHarness {
            static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
            static SvgSymbol read(String xml)throws Exception{return SvgSymbol.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));}
            static int peak(BufferedImage image){int peak=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)peak=Math.max(peak,image.getRGB(x,y)>>>24);return peak;}
            static void close(float a,float b){check(Math.abs(a-b)<.01f,a+" != "+b);}
            public static void main(String[] args)throws Exception{
                File assets=new File(args[0]);Context c=new Context(assets);File symbols=new File(assets,"symbols");
                File[] files=symbols.listFiles((d,name)->name.endsWith(".svg")&&!name.startsWith("bad")&&!name.equals("apple_tv_device.svg")&&!name.startsWith("wifi"));
                check(files.length==32,"32 reference-matched SVG symbols");double worst=0;
                for(File file:files){String name=file.getName().replace(".svg","");SvgSymbol icon;
                    try(InputStream in=new FileInputStream(file)){icon=SvgSymbol.read(in);}
                    BufferedImage png=ImageIO.read(new File(symbols,name+".png"));
                    check(icon.width==png.getWidth()&&icon.height==png.getHeight(),"source dimensions: "+name);
                    Canvas canvas=new Canvas(png.getWidth(),png.getHeight());Paint paint=new Paint(1);
                    icon.draw(canvas,0,0,icon.width,icon.height,0xffffffff,paint);
                    check(canvas.paths>0&&canvas.depth()==0,"vector paths and balanced Canvas state: "+name);
                    double error=0;for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)
                        error+=Math.abs((png.getRGB(x,y)>>>24)-(canvas.image.getRGB(x,y)>>>24));
                    error/=png.getWidth()*png.getHeight();worst=Math.max(worst,error);
                    check(error<6.5,"native alpha reconstruction error "+name+": "+error);
                    float width=Symbols.width(c,name,40),height=Symbols.height(c,name,40);
                    close(width/height,icon.width/icon.height);close(Symbols.aspect(c,name),icon.width/icon.height);
                    Canvas large=new Canvas(800,800);Symbols.draw(c,large,name,400,400,200,0xff123456);
                    check(large.paths>0&&large.depth()==0,"draw paths at enlarged size: "+name);
                }
                long previousCoverage=-1;
                for(int level=0;level<4;level++){
                    Canvas signal=new Canvas(160,112);Symbols.draw(c,signal,"wifi_"+level,80,56,144,0xff123456);
                    check(signal.paths==3&&signal.depth()==0,"Wi-Fi uses three cached vector layers");
                    long coverage=0;for(int y=0;y<signal.image.getHeight();y++)for(int x=0;x<signal.image.getWidth();x++)coverage+=signal.image.getRGB(x,y)>>>24;
                    check(coverage>previousCoverage,"each Wi-Fi level lights another component");previousCoverage=coverage;
                    close(Symbols.aspect(c,"wifi_"+level),40f/28f);
                }
                Canvas device=new Canvas(800,800);Symbols.draw(c,device,"apple_tv_device",400,400,200,0xff123456);
                check(device.paths>0&&device.depth()==0,"device symbol renders as native paths");
                close(Symbols.aspect(c,"apple_tv_device"),1f);
                check(BitmapFactory.decodes==0,"all reference symbols loaded as SVG, zero PNG decoding");
                check(Bitmap.allocations==0,"ordinary vector drawing does not allocate bitmap masks");
                close(Symbols.width(c,"home",40),37.5f);close(Symbols.width(c,"wifi",36),38);
                int opens=c.getAssets().count();for(int i=0;i<100;i++)Symbols.draw(c,new Canvas(100,100),"wifi",50,50,36,0xaaffffff);
                check(opens==c.getAssets().count(),"cache avoids repeated XML/asset reads");
                Bitmap mask=Symbols.mask(c,"cloud",72,48);check(mask.getWidth()==72&&mask.getHeight()==48,"output-sized shadow mask");
                check(peak(mask.image)>=215&&peak(mask.image)<=218,"source opacity preserved in weather mask");
                check(Symbols.width(c,"missing",40)==0&&Symbols.height(c,"missing",40)==0&&Symbols.aspect(c,"missing")==0,"missing dimensions");
                check(Symbols.mask(c,"missing",20,20)==null,"missing mask");
                SvgSymbol layered=read("<svg width='20' height='20' viewBox='0 0 20 20'><path d='M2 2L18 2L18 18L2 18Z' fill='white' opacity='.4'/><path d='M2 2L18 2L18 18L2 18Z' fill='white' opacity='.7'/></svg>");
                Canvas translucent=new Canvas(20,20);layered.draw(translucent,0,0,20,20,0x8044aaee,new Paint(1));
                int alpha=translucent.image.getRGB(10,10)>>>24;
                check(Math.abs(alpha-105)<=2&&translucent.layers==1,"uniform group tint for overlapping opacity layers: "+alpha);
                Canvas opaque=new Canvas(20,20);layered.draw(opaque,0,0,20,20,0xff44aaee,new Paint(1));
                check(opaque.layers==0,"no offscreen layer for opaque tint");
                SvgSymbol.path("M1e1,-2E0 L.5,+4.2 C1,2 3,4 5,6 Z");
                for(String bad:new String[]{"","m0 0l1 1","L1 1","M0 0 C1 2","MNaN 0","M0 0Z1 2","M1e 2"}){
                    try{SvgSymbol.path(bad);throw new AssertionError("accepted bad path: "+bad);}catch(IOException expected){}}
                try{read("<svg viewBox='0 0 20 20'><g/></svg>");throw new AssertionError("unsupported group");}catch(IOException expected){}
                // Invalid SVG must retain the old PNG asset fallback, not disappear silently.
                check(Symbols.width(c,"bad_fallback",36)>0&&BitmapFactory.decodes==1,"invalid SVG uses PNG fallback");
                ArrayList<Thread> threads=new ArrayList<>();java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
                for(int t=0;t<4;t++){final int color=t%2==0?0xffff0000:0xff00ff00;
                    Thread thread=new Thread(()->{try{for(int i=0;i<40;i++){Canvas target=new Canvas(100,100);Symbols.draw(c,target,"cloud",50,50,60,color);
                        check((target.image.getRGB(50,50)&0xffffff)==(color&0xffffff),"concurrent tint isolation");}}catch(Throwable e){failure.set(e);}});threads.add(thread);thread.start();}
                for(Thread thread:threads)thread.join();if(failure.get()!=null)throw new AssertionError(failure.get());
                System.out.println("PASS: SVGs, curves/holes, native alpha MAE <= "+worst+", tint, group opacity, geometry, cache, masks, fallback, concurrent drawing");
            }
        }''',
}

for name in ('SvgSymbol', 'Symbols'):
    sources[f'io/tvos/{name}.java'] = Path(f'app/src/io/tvos/{name}.java').read_text()

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    for name, source in sources.items():
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(source)
    shutil.copytree('app/assets/symbols', root / 'assets/symbols')
    (root / 'assets/symbols/bad_fallback.svg').write_text("<svg viewBox='0 0 1 1'><image/></svg>")
    shutil.copyfile('app/assets/symbols/cloud.png', root / 'assets/symbols/bad_fallback.png')
    subprocess.run(['java', 'com.sun.tools.javac.Main', '-d', directory, *[str(root / name) for name in sources]], check=True)
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', directory, 'io.tvos.SvgHarness', str(root / 'assets')], check=True)
