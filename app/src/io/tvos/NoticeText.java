package io.tvos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable resource keys for notices. Text matching is confined to migration of old records. */
final class NoticeText {
    private NoticeText() { }
    private static final Map<String,Integer> ids = new HashMap<>();
    private static final Map<String,String> exact = new HashMap<>();
    private static final List<Template> templates = new ArrayList<>();
    private static boolean initialized;
    private static final Pattern SLOT = Pattern.compile("%(\\d+)\\$([sd])");

    private static final class Template {
        final String key; final Pattern pattern; final int[] slots; final boolean[] numbers;
        Template(String key, String text) {
            this.key=key;
            Matcher m=SLOT.matcher(text);StringBuilder regex=new StringBuilder("\\A");
            List<Integer> indexes=new ArrayList<>();List<Boolean> numeric=new ArrayList<>();int end=0;
            while(m.find()) {
                regex.append(Pattern.quote(text.substring(end,m.start()))).append(m.group(2).equals("d")?"(-?\\d+)":"(.*?)");
                indexes.add(Integer.parseInt(m.group(1))-1);numeric.add(m.group(2).equals("d"));end=m.end();
            }
            regex.append(Pattern.quote(text.substring(end))).append("\\z");
            pattern=Pattern.compile(regex.toString(),Pattern.DOTALL);
            slots=new int[indexes.size()];numbers=new boolean[slots.length];
            for(int i=0;i<slots.length;i++){slots[i]=indexes.get(i);numbers[i]=numeric.get(i);}
        }
    }

    private static synchronized void init() {
        if(initialized)return;
        try {
            for(Field field:R.string.class.getDeclaredFields()) {
                if(field.getType()!=int.class)continue;
                int id=field.getInt(null);String key=field.getName();ids.put(key,id);
                for(AppLanguage language:AppLanguage.values()) {
                    String text=AppStrings.get(id,language);
                    if(SLOT.matcher(text).find())templates.add(new Template(key,text));
                    else if(!text.isEmpty()) {
                        exact.putIfAbsent(text,key);
                        if(text.endsWith(": ") || key.equals("runtime_the_tv_update_turned_back_on"))
                            templates.add(new Template(key,text+"%1$s"));
                    }
                }
            }
            initialized=true;
        } catch(IllegalAccessException e) { throw new IllegalStateException("Notice resource catalog inaccessible",e); }
    }

    static JSONObject message(int id,Object... values) throws JSONException {
        init();String key=null;
        for(Map.Entry<String,Integer> e:ids.entrySet())if(e.getValue()==id){key=e.getKey();break;}
        if(key==null)throw new IllegalArgumentException("Unknown notice resource");
        JSONArray args=new JSONArray();for(Object v:values)args.put(v);
        return new JSONObject().put("key",key).put("args",args);
    }

    private static JSONObject capture(String text) throws JSONException { return capture(text,0); }

    private static JSONObject capture(String text,int depth) throws JSONException {
        if(depth>4)return new JSONObject().put("raw",text);
        init();String key=exact.get(text);
        if(key!=null)return new JSONObject().put("key",key);
        for(Template t:templates) {
            Matcher m=t.pattern.matcher(text);if(!m.matches())continue;
            JSONArray args=new JSONArray();
            try {
                for(int i=0;i<t.slots.length;i++)args.put(t.slots[i],t.numbers[i]?Long.valueOf(m.group(i+1)):capture(m.group(i+1),depth+1));
                return new JSONObject().put("key",t.key).put("args",args);
            } catch(NumberFormatException ignored) { /* retain an unrecognized record verbatim */ }
        }
        return new JSONObject().put("raw",text);
    }

    static JSONObject error(Throwable failure) throws JSONException {
        if(failure instanceof ResourceFailure) {
            ResourceFailure known=(ResourceFailure)failure;
            JSONObject label=message(known.resource);
            return known.detail==null?label:join(java.util.Arrays.asList(label," ("+known.detail+")"),"","");
        }
        return new JSONObject().put("raw",failure.getMessage()==null?"":failure.getMessage());
    }

    static JSONObject join(List<?> values,String separator,String suffix) throws JSONException {
        JSONArray parts=new JSONArray();for(Object value:values)parts.put(value);
        return new JSONObject().put("parts",parts).put("separator",separator).put("suffix",suffix);
    }

    static void migrate(JSONArray notices) throws JSONException {
        if(notices==null)return;
        for(int i=0;i<notices.length();i++) {
            JSONObject notice=notices.optJSONObject(i);
            if(notice!=null&&!notice.has("message"))notice.put("message",capture(notice.optString("text","")));
        }
    }

    static String render(JSONObject notice) {
        JSONObject value=notice.optJSONObject("message");
        return renderValue(value,notice.optString("text",""),0);
    }

    private static String renderValue(JSONObject value,String fallback,int depth) {
        if(depth>8)return fallback;
        if(value==null)return fallback;
        JSONArray parts=value.optJSONArray("parts");
        if(parts!=null) {
            StringBuilder joined=new StringBuilder();
            for(int i=0;i<parts.length();i++) {
                if(i>0)joined.append(value.optString("separator",""));
                Object part=parts.opt(i);
                joined.append(part instanceof JSONObject?renderValue((JSONObject)part,fallback,depth+1):String.valueOf(part));
            }
            return joined.append(value.optString("suffix","")).toString();
        }
        if(value.has("raw"))return value.optString("raw","");
        init();Integer id=ids.get(value.optString("key",""));
        if(id==null)return fallback;
        String text=AppStrings.get(id);JSONArray args=value.optJSONArray("args");
        if(args==null||args.length()==0)return text;
        Object[] values=new Object[args.length()];
        for(int i=0;i<values.length;i++)values[i]=args.opt(i) instanceof JSONObject ? renderValue((JSONObject)args.opt(i),fallback,depth+1) : args.opt(i);
        if(!SLOT.matcher(text).find())return text+String.valueOf(values[0]);
        try{return String.format(AppLanguage.current().locale,text,values);}
        catch(java.util.IllegalFormatException e){return fallback;}
    }
}
