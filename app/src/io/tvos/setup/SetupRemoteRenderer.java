package io.tvos.setup;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
/** Cached full remote silhouette and button symbols from the supplied reference. */
public final class SetupRemoteRenderer {
 private final Path body = SetupArtwork.remote_details();
 public void draw(Canvas canvas,float x,float y,float height,Paint paint) {
  int saved=canvas.save();canvas.translate(x,y);canvas.scale(height/660,height/660);
  canvas.drawPath(body,paint);canvas.restoreToCount(saved);
 }
}
