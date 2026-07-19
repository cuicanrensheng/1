package com.huya.berry;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.duowan.auk.ArkValue;
import com.huya.live.utils.image.IImageLoader;
import com.huya.live.utils.image.IconListLoader;
import com.huya.live.utils.image.ImageLoaderListener;

import java.io.File;

/**
 * @author DW
 * @date 2020/5/21
 */
public class GlideImageLoader implements IImageLoader {
    private Context mContext;

    @Override
    public void init(Context context) {
        mContext = context;
    }

    /**
     * 圆形图片
     * @param imageView
     * @param url       下载地址
     * @param defaultImg 期望的默认图片
     * @param listener 监听器
     * @param context 上下文
     */
    @Override
    public void displayCircle(Context context, ImageView imageView, String url, int defaultImg, ImageLoaderListener listener){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(new RequestOptions()
                        .placeholder(defaultImg)
                        .error(defaultImg)
                        .transform(new GlideCircleTransform(context)))
                .listener(mRequestListener)
                .into(imageView);
    }

    /**
     * 图片
     * @param imageView
     * @param url       下载地址
     * @param context 上下文
     * @param listener 监听器
     * @param defaultImg 期望默认图片
     */
    @Override
    public void displayImage(Context context, ImageView imageView, String url, int defaultImg, ImageLoaderListener listener){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .apply(new RequestOptions()
                        .placeholder(defaultImg)
                        .error(defaultImg)
                        .dontAnimate())
                .load(url)
                .listener(mRequestListener)
                .into(imageView);
    }

    @Override
    public void displayEmpty(Context context, ImageView imageView, String url){
        Glide.with(context)
                .load(url)
                .apply(new RequestOptions()
                        .dontAnimate())
                .into(imageView);
    }

    /**
     * 椭圆图片
     * @param context 上下文
     * @param imageView
     * @param url       下载地址
     * @param listener 监听器
     * @param defaultImg 默认图片
     * @param radius 圆角
     */
    @Override
    public void displayRound(final Context context, ImageView imageView, String url,
                             ImageLoaderListener listener, int defaultImg, int radius){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(new RequestOptions()
                        .transform(new GlideRoundTransform(context, radius))
                        .placeholder(defaultImg)
                        .error(defaultImg)
                        .dontAnimate())
                .listener(mRequestListener)
                .into(imageView);
    }

    /**
     * 下载图片
     * @param context 上下文
     * @param url       下载地址
     * @param listener 监听器
     */
    @Override
    public void loadImage(Context context, String url, ImageLoaderListener listener){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .load(url)
                .listener(mRequestListener)
                .into(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);
    }

    /**
     * 下载图片
     * @param context 上下文
     * @param url       下载地址
     * @param width 宽
     * @param height 高
     */
    @Override
    public Bitmap loadImage(Context context, String url, int width, int height){
        try {
            return Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .into(width, height)
                    .get();
        }catch (Exception e){
            return null;
        }
    }

    /**
     * 下载本地图片
     * @param imageView
     * @param resId       下载地址
     * @param context 上下文
     * @param listener 监听器
     * @param defaultImg 期望默认图片
     */
    @Override
    public void displayImageFromRes(Context context, ImageView imageView, int resId,
                                    int defaultImg, ImageLoaderListener listener){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .load(resId)
                .apply(new RequestOptions()
                        .error(defaultImg)
                        .dontAnimate())
                .listener(mRequestListener)
                .into(imageView);
    }

    /**
     * 椭圆图片
     * @param context 上下文
     * @param imageView
     * @param file       下载地址
     * @param listener 监听器
     * @param defaultImg 默认图片
     * @param radius 圆角
     */
    @Override
    public void displayRound(final Context context, ImageView imageView, File file,
                             ImageLoaderListener listener, int defaultImg, int radius){
        mImageLoaderListener = listener;
        Glide.with(context)
                .asBitmap()
                .load(file)
                .apply(new RequestOptions()
                        .transform(new GlideRoundTransform(context, radius))
                        .placeholder(defaultImg)
                        .error(defaultImg)
                        .dontAnimate())
                .listener(mRequestListener)
                .into(imageView);
    }

    /**
     * 下载图片列表
     * @param context 上下文
     * @param url       下载地址
     * @param iconListLoader
     */
    @Override
    public void loadImage(Context context, String url, IconListLoader iconListLoader){
        mIconListLoader = iconListLoader;
        Glide.with(ArkValue.gContext)
                .asBitmap()
                .load(url)
                .into(target);
    }

    private SimpleTarget<Bitmap> target = new SimpleTarget<Bitmap>(IconListLoader.MAX_SIZE, IconListLoader.MAX_SIZE) {
        @Override
        public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> var2) {
            if(mIconListLoader != null){
                mIconListLoader.loadNext(bitmap);
            }
        }
    };

    private IconListLoader mIconListLoader;
    private ImageLoaderListener mImageLoaderListener;
    private RequestListener mRequestListener = new RequestListener() {

        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target target, boolean isFirstResource) {
            if(mImageLoaderListener != null){
                mImageLoaderListener.onFail();
            }
            return false;
        }
        @Override
        public boolean onResourceReady(Object resource, Object model, Target target, DataSource dataSource, boolean isFirstResource) {
            if(mImageLoaderListener != null){
                mImageLoaderListener.onLoadSuccess((Bitmap) resource);
            }
            return false;
        }
    };

}
