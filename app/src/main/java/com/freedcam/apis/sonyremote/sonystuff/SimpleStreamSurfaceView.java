/*
 *
 *     Copyright (C) 2015 Ingo Fuchs
 *     This program is free software; you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation; either version 2 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License along
 *     with this program; if not, write to the Free Software Foundation, Inc.,
 *     51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 * /
 */

package com.freedcam.apis.sonyremote.sonystuff;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.renderscript.Type.Builder;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;

import com.freedcam.apis.basecamera.parameters.modes.AbstractModeParameter.I_ModeParameterEvent;
import com.freedcam.apis.sonyremote.sonystuff.DataExtractor.FrameInfo;
import com.freedcam.apis.sonyremote.sonystuff.SimpleStreamSurfaceView.StreamErrorListener.StreamErrorReason;
import com.freedcam.utils.FreeDPool;
import com.freedcam.utils.Logger;
import com.imageconverter.ScriptC_brightness;
import com.imageconverter.ScriptC_contrast;
import com.imageconverter.ScriptC_focuspeak_argb;
import com.imageconverter.ScriptC_imagestack;
import com.imageconverter.ScriptC_starfinder;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;



/**
 * A SurfaceView based class to draw liveview frames serially.
 */
public class SimpleStreamSurfaceView extends SurfaceView implements Callback, I_ModeParameterEvent {

    private static final String TAG = SimpleStreamSurfaceView.class.getSimpleName();

    private boolean mWhileFetching;
    private final BlockingQueue<DataExtractor> mJpegQueue = new ArrayBlockingQueue<>(2);
    private final BlockingQueue<DataExtractor> frameQueue = new ArrayBlockingQueue<>(2);
    private final boolean mInMutableAvailable = VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB;
    private int mPreviousWidth = 0;
    private int mPreviousHeight = 0;
    private final Paint mFramePaint;
    private  Paint paint;
    private StreamErrorListener mErrorListener;
    public boolean focuspeak = false;
    public NightPreviewModes nightmode = NightPreviewModes.off;
    private int currentImageStackCount = 0;

    private RenderScript mRS;
    private Allocation mInputAllocation;
    private Allocation mInputAllocation2;
    private Allocation mOutputAllocation;
    private ScriptC_focuspeak_argb focuspeak_argb;
    private ScriptC_imagestack imagestack_argb;
    private ScriptC_brightness brightnessRS;
    private ScriptC_contrast contrastRS;
    private ScriptC_starfinder starfinderRS;
    private ScriptIntrinsicBlur blurRS;
    private Bitmap drawBitmap;
    private Bitmap stackBitmap;

    private int zoomPreviewMagineLeft =0;
    private int zoomPreviewMargineTop = 0;

    public int PreviewZOOMFactor = 1;

    public enum NightPreviewModes
    {
        on,
        off,
        grayscale,
        zoompreview,
    }

    private void initRenderScript()
    {
        drawBitmap = Bitmap.createBitmap(mPreviousWidth, mPreviousHeight, Config.ARGB_8888);
        stackBitmap = Bitmap.createBitmap(mPreviousWidth, mPreviousHeight, Config.ARGB_8888);
        Builder tbIn = new Builder(mRS, Element.RGBA_8888(mRS));
        tbIn.setX(mPreviousWidth);
        tbIn.setY(mPreviousHeight);
        Builder tbIn2 = new Builder(mRS, Element.RGBA_8888(mRS));
        tbIn2.setX(mPreviousWidth);
        tbIn2.setY(mPreviousHeight);

        Builder tbOut = new Builder(mRS, Element.RGBA_8888(mRS));
        tbOut.setX(mPreviousWidth);
        tbOut.setY(mPreviousHeight);

        mInputAllocation = Allocation.createTyped(mRS, tbIn.create(), MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mInputAllocation2 = Allocation.createTyped(mRS, tbIn.create(), MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(mRS, tbOut.create(), MipmapControl.MIPMAP_NONE,  Allocation.USAGE_SCRIPT);
    }


    /**
     * Constructor
     * 
     * @param context
     */
    public SimpleStreamSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(this);
        mFramePaint = new Paint();
        mFramePaint.setDither(true);
        initBitmaps(context);
    }

    /**
     * Constructor
     * 
     * @param context
     * @param attrs
     */
    public SimpleStreamSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        mFramePaint = new Paint();
        mFramePaint.setDither(true);
        initBitmaps(context);
    }

    /**
     * Constructor
     * 
     * @param context
     * @param attrs
     * @param defStyle
     */
    public SimpleStreamSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        getHolder().addCallback(this);
        mFramePaint = new Paint();
        mFramePaint.setDither(true);
        initBitmaps(context);
    }

    private void initBitmaps(Context context)
    {
        paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(5);
        paint.setStyle(Style.STROKE);
        if (VERSION.SDK_INT >= 18) {
            mRS = RenderScript.create(context);
            focuspeak_argb = new ScriptC_focuspeak_argb(mRS);
            imagestack_argb = new ScriptC_imagestack(mRS);
            brightnessRS = new ScriptC_brightness(mRS);
            contrastRS = new ScriptC_contrast(mRS);
            blurRS = ScriptIntrinsicBlur.create(mRS, Element.U8_4(mRS));
            starfinderRS = new ScriptC_starfinder(mRS);
        }

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // do nothing.
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // do nothing.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mWhileFetching = false;
    }

    /**
     * Start retrieving and drawing liveview frame data by new threads.
     * 
     * @return true if the starting is completed successfully, false otherwise.

     */
    public void start(final String streamUrl, StreamErrorListener listener) {
        mErrorListener = listener;

        if (streamUrl == null) {
            Logger.e(TAG, "start() streamUrl is null.");
            mWhileFetching = false;
            mErrorListener.onError(StreamErrorReason.OPEN_ERROR);
            return;
        }
        if (mWhileFetching) {
            Logger.d(TAG, "start() already starting.");
            return;
        }

        mWhileFetching = true;

        // A thread for retrieving liveview data from server.
        FreeDPool.Execute(new Runnable() {
            @Override
            public void run() {
                Logger.d(TAG, "Starting retrieving streaming data from server.");
                SimpleLiveviewSlicer slicer = null;

                try {

                    // Create Slicer to open the stream and parse it.
                    slicer = new SimpleLiveviewSlicer();
                    slicer.open(streamUrl);

                    while (mWhileFetching)
                    {
                        fetchPayLoad(slicer);
                    }
                } catch (IOException e) {
                    Logger.d(TAG, "IOException while fetching: " + e.getMessage());
                    mErrorListener.onError(StreamErrorReason.IO_EXCEPTION);
                } finally {
                    if (slicer != null) {
                        slicer.close();
                    }


                    mJpegQueue.clear();
                    frameQueue.clear();
                    mWhileFetching = false;
                }
            }
        });

        // A thread for drawing liveview frame fetched by above thread.
        FreeDPool.Execute(new Runnable() {
            @Override
            public void run() {
                Logger.d(TAG, "Starting drawing stream frame.");
                Bitmap frameBitmap = null;

                Options factoryOptions = new Options();
                factoryOptions.inSampleSize = 1;
                factoryOptions.inPreferQualityOverSpeed = true;
                factoryOptions.inDither = false;
                factoryOptions.inScaled = false;

                if (mInMutableAvailable) {
                    initInBitmap(factoryOptions);
                }

                while (mWhileFetching)
                {
                    DataExtractor dataExtractor = null;
                    DataExtractor frameExtractor =null;
                    try {
                        dataExtractor = mJpegQueue.take();
                        if (!frameQueue.isEmpty())
                            frameExtractor = frameQueue.take();


                    } catch (IllegalArgumentException e) {
                        if (mInMutableAvailable) {
                            clearInBitmap(factoryOptions);
                        }
                        continue;
                    } catch (InterruptedException e) {
                        Logger.e(TAG, "Drawer thread is Interrupted.");
                        break;
                    }
                    frameBitmap = BitmapFactory.decodeByteArray(dataExtractor.jpegData, 0, dataExtractor.jpegData.length, factoryOptions);

                    drawFrame(frameBitmap, dataExtractor, frameExtractor);
                }

                if (frameBitmap != null) {
                    frameBitmap.recycle();
                }
                mWhileFetching = false;
            }
        });
    }

    private void fetchPayLoad(SimpleLiveviewSlicer slicer) throws IOException {
        DataExtractor payload = slicer.nextDataExtractor();
        if (payload.commonHeader == null) { // never occurs
            return;
        }
        if (payload.commonHeader.PayloadType == 1)
        {
            if (mJpegQueue.size() == 2) {
                mJpegQueue.remove();
            }
            mJpegQueue.add(payload);
        }
        if (payload.commonHeader.PayloadType == 2) {
            if (frameQueue.size() == 2) {
                frameQueue.remove();
            }
            frameQueue.add(payload);
        }
    }


    private int convert(int length, int val)
    {
        double pro = (double)val /(double)10000 * 100;
        double newret = (double)length /100 * pro;
        return (int)newret;
    }

    /**
     * Request to stop retrieving and drawing liveview data.
     */
    public void stop() {
        mWhileFetching = false;

    }

    /**
     * Check to see whether start() is already called.
     * 
     * @return true if start() is already called, false otherwise.
     */
    public boolean isStarted() {
        return mWhileFetching;
    }

    @TargetApi(VERSION_CODES.HONEYCOMB)
    private void initInBitmap(Options options) {
        options.inBitmap = null;
        options.inMutable = true;
    }

    @TargetApi(VERSION_CODES.HONEYCOMB)
    private void clearInBitmap(Options options) {
        if (options.inBitmap != null) {
            options.inBitmap.recycle();
            options.inBitmap = null;
        }
    }

    @TargetApi(VERSION_CODES.HONEYCOMB)
    private void setInBitmap(Options options, Bitmap bitmap) {
        options.inBitmap = bitmap;
    }

    /**
     * Draw frame bitmap onto a canvas.
     * 
     * @param frame
     */
    @TargetApi(VERSION_CODES.JELLY_BEAN_MR2)
    private void drawFrame(Bitmap frame, DataExtractor dataExtractor, DataExtractor frameExtractor)
    {
        try {
            if (frame.getWidth() != mPreviousWidth || frame.getHeight() != mPreviousHeight) {
                onDetectedFrameSizeChanged(frame.getWidth(), frame.getHeight());
                return;
            }

            //canvas.drawColor(Color.BLACK);
            int w = frame.getWidth();
            int h = frame.getHeight();
            Rect src = new Rect(0, 0, w, h);
            if (PreviewZOOMFactor > 1)
            {
                int w4 = w /PreviewZOOMFactor;
                int h4 = h/PreviewZOOMFactor;
                int wCenter = w/2;
                int hCenter = h/2;
                int frameleft = wCenter -w4 + zoomPreviewMagineLeft;
                int frameright = wCenter +w4 + zoomPreviewMagineLeft;
                int frametop = hCenter -h4 + zoomPreviewMargineTop;
                int framebottom = hCenter +h4 + zoomPreviewMargineTop;

                if (frameleft < 0)
                {
                    int dif = frameleft * -1;
                    frameleft +=dif;
                    frameright +=dif;
                    Logger.d(TAG, "zoommargineLeft = " + zoomPreviewMagineLeft);
                    zoomPreviewMagineLeft +=dif;
                    Logger.d(TAG, "zoommargineLeft = " + zoomPreviewMagineLeft);
                    Log.d(TAG, "frameleft < 0");
                }
                if (frameright > w)
                {
                    int dif = frameright - w;
                    frameright -=dif;
                    frameleft -=dif;
                    Logger.d(TAG, "zoommargineLeft = " + zoomPreviewMagineLeft);
                    zoomPreviewMagineLeft -=dif;
                    Logger.d(TAG, "zoommargineLeft = " + zoomPreviewMagineLeft);
                    Log.d(TAG, "frameright > w");
                }
                if (frametop < 0)
                {
                    int dif = frametop * -1;
                    frametop +=dif;
                    framebottom +=dif;
                    Logger.d(TAG, "zoomPreviewMargineTop = " + zoomPreviewMargineTop);
                    zoomPreviewMargineTop +=dif;
                    Logger.d(TAG, "zoomPreviewMargineTop = " + zoomPreviewMargineTop);
                    Log.d(TAG, "framebottom < 0");
                }
                if (framebottom > h)
                {
                    int dif = framebottom -h;
                    framebottom -=dif;
                    frametop -= dif;
                    Logger.d(TAG, "zoomPreviewMargineTop = " + zoomPreviewMargineTop);
                    zoomPreviewMargineTop -=dif;
                    Logger.d(TAG, "zoomPreviewMargineTop = " + zoomPreviewMargineTop);
                    Log.d(TAG, "framebottom > h");
                }

                src = new Rect(frameleft,frametop,frameright,framebottom);
                //Logger.d(TAG, src.flattenToString());
                mInputAllocation.copyFrom(frame);
                blurRS.setInput(mInputAllocation);
                blurRS.setRadius(0.3f);
                blurRS.forEach(mOutputAllocation);
                mOutputAllocation.copyTo(drawBitmap);
            }


            float by = Math.min((float) getWidth() / w, (float) getHeight() / h);
            int offsetX = (getWidth() - (int) (w * by)) / 2;
            int offsetY = (getHeight() - (int) (h * by)) / 2;
            Rect dst = new Rect(offsetX, offsetY, getWidth() - offsetX, getHeight() - offsetY);
            if (nightmode == NightPreviewModes.on)
            {
                if(!drawNightPreview(frame, frameExtractor, src, dst))
                    return;
            }
            else if (nightmode == NightPreviewModes.grayscale)
            {
                mInputAllocation.copyFrom(frame);
                blurRS.setInput(mInputAllocation);
                blurRS.setRadius(1.5f);
                blurRS.forEach(mOutputAllocation);
                mInputAllocation.copyFrom(mOutputAllocation);
                starfinderRS.set_gCurrentFrame(mInputAllocation);
                starfinderRS.forEach_processBrightness(mOutputAllocation);
                mOutputAllocation.copyTo(drawBitmap);

            }
            if (focuspeak) {
                if (nightmode != NightPreviewModes.off || PreviewZOOMFactor > 1)
                    mInputAllocation.copyFrom(drawBitmap);
                else
                    mInputAllocation.copyFrom(frame);
                focuspeak_argb.set_gCurrentFrame(mInputAllocation);
                focuspeak_argb.forEach_peak(mOutputAllocation);
                mOutputAllocation.copyTo(drawBitmap);

            }
            Canvas canvas = getHolder().lockCanvas();
            if (canvas == null) {
                return;
            }
            if (nightmode != NightPreviewModes.off || focuspeak)
                canvas.drawBitmap(drawBitmap, src, dst, mFramePaint);
            else
                canvas.drawBitmap(frame, src, dst, mFramePaint);
            if (frameExtractor != null)
                drawFrameInformation(frameExtractor, canvas, dst);

            getHolder().unlockCanvasAndPost(canvas);
        }
        catch(IllegalStateException ex)
        {Logger.exception(ex);}
    }

    @TargetApi(VERSION_CODES.JELLY_BEAN_MR2)
    private boolean drawNightPreview(Bitmap frame, DataExtractor frameExtractor, Rect src, Rect dst) {
        mInputAllocation.copyFrom(frame);
        blurRS.setInput(mInputAllocation);
        blurRS.setRadius(1.5f);
        blurRS.forEach(mOutputAllocation);
        mInputAllocation.copyFrom(mOutputAllocation);
        if (currentImageStackCount == 0)
            mInputAllocation2.copyFrom(frame);
        else
            mInputAllocation2.copyFrom(drawBitmap);
        imagestack_argb.set_gCurrentFrame(mInputAllocation);
        imagestack_argb.set_gLastFrame(mInputAllocation2);
        imagestack_argb.forEach_stackimage_avarage(mOutputAllocation);
        mOutputAllocation.copyTo(drawBitmap);

        if (currentImageStackCount < 3)
            currentImageStackCount++;
        else
            currentImageStackCount = 0;
        if (currentImageStackCount < 3)
            return false;
        else
        {
            mInputAllocation.copyFrom(drawBitmap);
            brightnessRS.set_gCurrentFrame(mInputAllocation);
            brightnessRS.set_brightness(100 / 255.0f);
            brightnessRS.forEach_processBrightness(mOutputAllocation);
            mOutputAllocation.copyTo(drawBitmap);
            mInputAllocation.copyFrom(drawBitmap);
            contrastRS.set_gCurrentFrame(mInputAllocation);
            contrastRS.invoke_setBright(200f);
            contrastRS.forEach_processContrast(mOutputAllocation);
            mOutputAllocation.copyTo(drawBitmap);
            return true;
        }
    }

    private void drawFrameInformation(DataExtractor dataExtractor, Canvas canvas, Rect dst)
    {
        if (dataExtractor.frameInfoList == null)
            return;
        for (int i=0; i< dataExtractor.frameInfoList.size(); i++)
        {
            FrameInfo frameInfo =  dataExtractor.frameInfoList.get(i);
            int w = getWidth();
            int h = getHeight();
            int top = convert(h, frameInfo.Top);
            int left = convert(w, frameInfo.Left);
            int right =convert(w,frameInfo.Right);
            int bottom = convert(h,frameInfo.Bottom);
            if (frameInfo.Category == 0x01)
            {
                dst = new Rect(left, top, right, bottom);
                //Rect src = new Rect(0, 0, crosshairs[0].getWidth(), crosshairs[0].getHeight());
                if (frameInfo.Status == 0x01)
                    paint.setColor(Color.BLUE);
                    //canvas.drawBitmap(crosshairs[0], src, dst, mFramePaint);
                if (frameInfo.Status == 0x00)
                    paint.setColor(Color.RED);
                    //canvas.drawBitmap(crosshairs[1], src, dst, mFramePaint);
                if (frameInfo.Status == 0x04)
                    paint.setColor(Color.GREEN);
                    //canvas.drawBitmap(crosshairs[2], src, dst, mFramePaint);
            }
            else if (frameInfo.Category == 0x05 ||frameInfo.Category == 0x04)
            {
                paint.setColor(Color.BLUE);

            }
            canvas.drawRect(left, top, right, bottom, paint);

        }
    }

    /**
     * Called when the width or height of liveview frame image is changed.
     * 
     * @param width
     * @param height
     */
    private void onDetectedFrameSizeChanged(int width, int height) {
        Logger.d(TAG, "Change of aspect ratio detected");
        mPreviousWidth = width;
        mPreviousHeight = height;
        initRenderScript();
        drawBlackFrame();
        drawBlackFrame();
        drawBlackFrame(); // delete triple buffers

    }


    /**
     * Draw black screen.
     */
    private void drawBlackFrame() {
        Canvas canvas = getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setStyle(Style.FILL);

        canvas.drawRect(new Rect(0, 0, getWidth(), getHeight()), paint);
        getHolder().unlockCanvasAndPost(canvas);
    }

    @Override
    public void onValueChanged(String val) {

    }

    @Override
    public void onIsSupportedChanged(boolean isSupported) {

    }

    @Override
    public void onIsSetSupportedChanged(boolean isSupported) {

    }

    @Override
    public void onValuesChanged(String[] values) {

    }

    @Override
    public void onVisibilityChanged(boolean visible) {

    }

    public interface StreamErrorListener {

        enum StreamErrorReason {
            IO_EXCEPTION,
            OPEN_ERROR,
        }

        void onError(StreamErrorReason reason);
    }

    private int startX;
    private int startY;
    private int currentX;
    private int currentY;
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                //action down resets all already set values and get the new one from the event
                startX = (int) event.getX();
                startY = (int) event.getY();
                //reset swipeDetected to false
                break;
            case MotionEvent.ACTION_MOVE:
                //in case action down never happend
                if (startX == 0 && startY == 0) {
                    startX = (int) event.getX();
                    startY = (int) event.getY();
                    //reset swipeDetected to false
                }
                currentX = (int) event.getX();
                currentY = (int) event.getY();
                if (startX > currentX)
                {
                    zoomPreviewMagineLeft -= (startX - currentX)/PreviewZOOMFactor;
                }
                else
                    zoomPreviewMagineLeft += (currentX -startX)/PreviewZOOMFactor;
                if (startY > currentY)
                    zoomPreviewMargineTop -= (startY - currentY)/PreviewZOOMFactor;
                else
                    zoomPreviewMargineTop+= (currentY - startY)/PreviewZOOMFactor;
                startX = currentX;
                startY = currentY;
                //detect swipeDetected. if swipeDetected detected return false else true
                break;
            case MotionEvent.ACTION_UP:
                startY = 0;
                startX = 0;
                break;
        }
        return  super.onTouchEvent(event);
    }
}
