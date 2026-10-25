package io.tvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/** Immutable, tintable paths for the bundled monochrome SVG subset. Not a general SVG engine. */
final class SvgSymbol {
    final float width, height;
    private final float minX, minY, viewportWidth, viewportHeight;
    private final Layer[] layers;

    private static final class Layer {
        final Path path;
        final float opacity;
        Layer(Path path, float opacity) { this.path = path; this.opacity = opacity; }
    }

    private SvgSymbol(float width, float height, float[] viewport, ArrayList<Layer> layers) {
        this.width = width; this.height = height;
        minX = viewport[0]; minY = viewport[1];
        viewportWidth = viewport[2]; viewportHeight = viewport[3];
        this.layers = layers.toArray(new Layer[0]);
    }

    static SvgSymbol read(InputStream input) throws IOException {
        try {
            XmlPullParser xml = Xml.newPullParser();
            xml.setInput(input, "UTF-8");
            ArrayList<Layer> layers = new ArrayList<>();
            float width = 0, height = 0;
            float[] viewport = null;
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if ("svg".equals(xml.getName()) && viewport == null) {
                    Numbers numbers = new Numbers(required(xml, "viewBox"));
                    viewport = new float[] { numbers.next(), numbers.next(), numbers.next(), numbers.next() };
                    if (numbers.more() || viewport[2] <= 0 || viewport[3] <= 0) throw new IOException("Invalid viewBox");
                    width = scalar(xml.getAttributeValue(null, "width"), viewport[2]);
                    height = scalar(xml.getAttributeValue(null, "height"), viewport[3]);
                    if (width <= 0 || height <= 0) throw new IOException("Invalid SVG size");
                } else if ("path".equals(xml.getName()) && viewport != null) {
                    if (xml.getAttributeValue(null, "transform") != null || xml.getAttributeValue(null, "style") != null
                            || xml.getAttributeValue(null, "stroke") != null) throw new IOException("Unsupported SVG path style");
                    String fill = xml.getAttributeValue(null, "fill");
                    if (fill != null && !fill.equalsIgnoreCase("white") && !fill.equalsIgnoreCase("#ffffff"))
                        throw new IOException("Expected white tintable SVG");
                    Path path = path(required(xml, "d"));
                    String rule = xml.getAttributeValue(null, "fill-rule");
                    if ("evenodd".equals(rule)) path.setFillType(Path.FillType.EVEN_ODD);
                    else if (rule != null && !rule.equals("nonzero")) throw new IOException("Unsupported SVG fill rule");
                    float opacity = scalar(xml.getAttributeValue(null, "opacity"), 1);
                    float fillOpacity = scalar(xml.getAttributeValue(null, "fill-opacity"), 1);
                    if (opacity < 0 || opacity > 1 || fillOpacity < 0 || fillOpacity > 1) throw new IOException("Invalid SVG opacity");
                    layers.add(new Layer(path, opacity * fillOpacity));
                } else throw new IOException("Unsupported SVG element: " + xml.getName());
            }
            if (viewport == null || layers.isEmpty()) throw new IOException("Empty SVG symbol");
            return new SvgSymbol(width, height, viewport, layers);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid SVG symbol", e); }
    }

    void draw(Canvas canvas, float left, float top, float width, float height, int color, Paint paint) {
        if (!(width > 0) || !(height > 0) || (color >>> 24) == 0) return;
        int saved = canvas.save();
        try {
            int alpha = color >>> 24;
            // Uniform tint alpha belongs to the whole icon, not to each overlapping opacity layer.
            // Only HDMI/speaker need this tiny bounded layer; single-path icons draw directly.
            if (layers.length > 1 && alpha < 255) {
                canvas.saveLayerAlpha(left, top, left + width, top + height, alpha);
                alpha = 255;
            }
            canvas.translate(left, top);
            canvas.scale(width / viewportWidth, height / viewportHeight);
            canvas.translate(-minX, -minY);
            paint.setColor(color);
            for (Layer layer : layers) {
                paint.setAlpha(Math.round(alpha * layer.opacity));
                canvas.drawPath(layer.path, paint);
            }
        } finally {
            canvas.restoreToCount(saved);
            paint.setColor(color);
        }
    }

    private static String required(XmlPullParser xml, String name) throws IOException {
        String value = xml.getAttributeValue(null, name);
        if (value == null || value.isEmpty()) throw new IOException("Missing SVG " + name);
        return value;
    }

    private static float scalar(String value, float fallback) throws IOException {
        if (value == null) return fallback;
        Numbers numbers = new Numbers(value);
        float number = numbers.next();
        if (numbers.more()) throw new IOException("Invalid SVG number");
        return number;
    }

    /** Only the absolute M/L/C/Z commands emitted by our checked-in symbol exports are accepted. */
    static Path path(String data) throws IOException {
        Path path = new Path();
        Numbers numbers = new Numbers(data);
        char command = 0;
        boolean moved = false;
        while (numbers.more()) {
            char next = numbers.peek();
            if (Character.isLetter(next)) { command = next; numbers.position++; }
            switch (command) {
                case 'M': path.moveTo(numbers.next(), numbers.next()); moved = true; command = 'L'; break;
                case 'L':
                    if (!moved) throw new IOException("SVG path must start with M");
                    path.lineTo(numbers.next(), numbers.next()); break;
                case 'C':
                    if (!moved) throw new IOException("SVG path must start with M");
                    path.cubicTo(numbers.next(), numbers.next(), numbers.next(), numbers.next(), numbers.next(), numbers.next()); break;
                case 'Z':
                    if (!moved) throw new IOException("Empty SVG contour");
                    path.close(); command = 0; break;
                default: throw new IOException("Unsupported SVG command: " + command);
            }
        }
        if (!moved) throw new IOException("Empty SVG path");
        return path;
    }

    private static final class Numbers {
        final String text;
        int position;
        Numbers(String text) { this.text = text; }
        boolean more() {
            while (position < text.length() && (Character.isWhitespace(text.charAt(position)) || text.charAt(position) == ',')) position++;
            return position < text.length();
        }
        char peek() { return text.charAt(position); }
        float next() throws IOException {
            if (!more()) throw new IOException("Missing SVG coordinate");
            int start = position;
            if (peek() == '+' || peek() == '-') position++;
            boolean digit = false;
            while (position < text.length() && Character.isDigit(text.charAt(position))) { position++; digit = true; }
            if (position < text.length() && text.charAt(position) == '.') {
                position++;
                while (position < text.length() && Character.isDigit(text.charAt(position))) { position++; digit = true; }
            }
            if (!digit) throw new IOException("Invalid SVG coordinate");
            if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                int exponent = position;
                while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
                if (position == exponent) throw new IOException("Invalid SVG exponent");
            }
            try {
                float value = Float.parseFloat(text.substring(start, position));
                if (!Float.isFinite(value)) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException e) { throw new IOException("Invalid SVG number", e); }
        }
    }
}
