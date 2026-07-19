package com.huya.berry;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

public class GlideCircleTransform extends BitmapTransformation {
    private static final String ID = "com.huya.live.utils.image.GlideRoundTransform";
    private static final byte[] ID_BYTES = ID.getBytes(CHARSET);

    public GlideCircleTransform(Context context) {
        super();
    }

    @Override
    protected Bitmap transform(BitmapPool pool, Bitmap toTransform, int outWidth, int outHeight) {
        return circleCrop(pool, toTransform);
    }

    public static Bitmap circleCrop(BitmapPool pool, Bitmap source) {
        if(source==null)return null;
        int width = source.getWidth();
        int height = source.getHeight();

        int roundPixels;
        Rect rect;
        if (width > height) {
            roundPixels = height;
            rect = new Rect((width - roundPixels) / 2, 0,
                    (width + roundPixels) / 2, roundPixels);
        } else {
            roundPixels = width;
            rect = new Rect(0, (height - roundPixels) / 2, roundPixels,
                    (height + roundPixels) / 2);
        }
        Bitmap output = pool.get(roundPixels, roundPixels,
                Bitmap.Config.ARGB_8888);
        if(output==null){
            output = Bitmap.createBitmap(roundPixels, roundPixels,
                    Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(output);

        Paint paint = new Paint();
        RectF rectF = new RectF(0, 0, roundPixels, roundPixels);

        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(rectF, roundPixels, roundPixels, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, rect, rectF, paint);
        return output;
    }

    @Override
    public void updateDiskCacheKey(MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }

    @Override
    public int hashCode() {
        return ID.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof GlideCircleTransform) {
            return true;
        }

        return super.equals(obj);
    }
}