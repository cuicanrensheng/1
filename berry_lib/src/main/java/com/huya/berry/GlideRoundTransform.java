package com.huya.berry;

/**
 * Created by DW on 2017/4/12.
 */

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/**
 * Glide 圆形图片 Transform
 */

public class GlideRoundTransform extends BitmapTransformation {

    private static final String ID = "com.huya.live.utils.image.GlideRoundTransform";
    private static final byte[] ID_BYTES = ID.getBytes(CHARSET);
    public int mRadius = 10;

    public GlideRoundTransform(Context context, int radius) {
        super();
        mRadius = radius;
    }

    @Override
    protected Bitmap transform(BitmapPool pool, Bitmap toTransform, int outWidth, int outHeight) {
        return roundCrop(pool, toTransform);
    }

    public Bitmap roundCrop(BitmapPool pool, Bitmap source) {
        if(source==null){
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();

        Bitmap output = pool.get( width,height,
                Bitmap.Config.ARGB_8888);
        if(output == null){
            output = Bitmap.createBitmap(width,height,
                    Bitmap.Config.ARGB_8888);
        }

        //产生一个同样大小的画布
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(0xFF000000);

        RectF rect = new RectF(0, 0, width,height);
        canvas.drawRoundRect(rect, mRadius, mRadius, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, 0, 0, paint);

        return output;
    }

    @Override
    public void updateDiskCacheKey(MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }

    @Override
    public int hashCode() {
        return (ID + "_" + mRadius).hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof GlideRoundTransform) {
            GlideRoundTransform other = (GlideRoundTransform) o;
            return mRadius == other.mRadius;
        }
        return false;
    }
}