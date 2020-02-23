package org.easydarwin.easypusher;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import com.squareup.otto.Subscribe;
import com.tencent.bugly.crashreport.CrashReport;

import org.easydarwin.encode.AudioStream;
import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.push.Pusher;
import org.easydarwin.easypusher.push.MediaStream;
import org.easydarwin.easypusher.util.Config;
import org.easydarwin.easypusher.util.SPUtil;
import org.easydarwin.util.BUSUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME;

import static org.easydarwin.easypusher.push.MediaStream.listEncoders;
import static org.easydarwin.easypusher.BuildConfig.RTMP_KEY;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class RecordService extends Service {

    private static final String TAG = "RecordService";

    // 生成悬浮窗
    private WindowManager wm;
    private WindowManager.LayoutParams param;
    private View mLayout;//要引用的布局文件.
    private GestureDetector mGD;

    // 录屏
    private MediaProjectionManager mMpmngr;
    private MediaProjection mMpj;
    private VirtualDisplay mVirtualDisplay;

    private int windowWidth;
    private int windowHeight;
    private int screenDensity;

    private Surface mSurface;
    private MediaCodec mMediaCodec;
    private ByteBuffer[] outputBuffers;

    final AudioStream audioStream = AudioStream.getInstance(EasyApplication.getEasyApplication());

    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public static Pusher mEasyPusher;
    private Thread mPushThread;
    private byte[] mPpsSps;
    private WindowManager mWindowManager;

    private MediaStream.CodecInfo ci;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 1.获取MediaProjectionManager实例
        mMpmngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);

        createEnvironment();

        mWindowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        // 在Android 6.0后，Android需要动态获取权限，若没有权限，则不启用该service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                return;
            }
        }

        try {
            configureMedia();
            startPush();
            showView();
        } catch (IOException e) {
            e.printStackTrace();
            CrashReport.postCatchedException(e);
        }

        BUSUtil.BUS.register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        BUSUtil.BUS.unregister(this);

        hideView();
        stopPush();
        release();

        if (mMpj != null) {
            mMpj.stop();
        }

        super.onDestroy();
    }

    /**
     * 生成悬浮窗
     * */
    private void createEnvironment() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowWidth = wm.getDefaultDisplay().getWidth();
        windowHeight = wm.getDefaultDisplay().getHeight();

        DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(displayMetrics);

        screenDensity = displayMetrics.densityDpi;

        // 1倍屏幕大小,0.75倍屏幕大小,0.5倍屏幕大小,0.3倍屏幕大小,0.25倍屏幕大小,0.2倍屏幕大小
        int defaultIdx = SPUtil.getScreenPushingResIndex(this);

        switch (defaultIdx) {
            case 0:
                break;
            case 1:
                windowWidth *= 0.75;
                windowHeight *= 0.75;
                break;
            case 2:
                windowWidth *= 0.5;
                windowHeight *= 0.5;
                break;
            case 3:
                windowWidth *= 0.3;
                windowHeight *= 0.3;
                break;
            case 4:
                windowWidth *= 0.25;
                windowHeight *= 0.25;
                break;
            case 5:
                windowWidth *= 0.2;
                windowHeight *= 0.2;
                break;
        }

        windowWidth /= 16;
        windowWidth *= 16;

        windowHeight /= 16;
        windowHeight *= 16;
    }

    private void configureMedia() throws IOException {
        ArrayList<MediaStream.CodecInfo> infoList = listEncoders("video/avc");
        ci = infoList.get(0);

        int bitrate = 72 * 1000 + SPUtil.getBitrateKbps(this);

        // 初始化MediaCodec，获取Surface对象
        mMediaCodec = MediaCodec.createByCodecName(ci.mName);

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", windowWidth, windowHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 20000000);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // 获取Surface对象
        mSurface = mMediaCodec.createInputSurface();

        mMediaCodec.start();
    }

    private void startPush() {
        if (mPushThread != null)
            return;

        mPushThread = new Thread("RecordService") {
            @Override
            public void run() {
                String url = Config.getServerURL(RecordService.this);
                boolean mHevc = SPUtil.getHevcCodec(RecordService.this);
                mEasyPusher = new EasyRTMP(mHevc ? EasyRTMP.VIDEO_CODEC_H265 : EasyRTMP.VIDEO_CODEC_H264, RTMP_KEY);

                try {
                    mEasyPusher.initPush(url,
                            getApplicationContext(),
                            code -> BUSUtil.BUS.post(new PushCallback(code))
                    );
                } catch (Exception ex) {
                    ex.printStackTrace();
                    BUSUtil.BUS.post(new PushCallback(EasyRTMP.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY));
                    mEasyPusher = null;
                    return;
                }

                try {
                    audioStream.setEnableAudio(SPUtil.getEnableAudio(EasyApplication.getEasyApplication()));
                    audioStream.addPusher(mEasyPusher);

                    byte[] h264 = new byte[windowWidth * windowHeight];
                    long lastKeyFrameUS = 0, lastRequestKeyFrameUS = 0;

                    while (mPushThread != null) {
                        if (lastKeyFrameUS > 0 && SystemClock.elapsedRealtimeNanos() / 1000 - lastKeyFrameUS >= 3000000) {  // 3s no key frame.
                            if (SystemClock.elapsedRealtimeNanos() / 1000 - lastRequestKeyFrameUS >= 3000000) {
                                Bundle p = new Bundle();
                                p.putInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                mMediaCodec.setParameters(p);

                                Log.i(TAG, "request key frame");

                                lastRequestKeyFrameUS = SystemClock.elapsedRealtimeNanos() / 1000;
                            }
                        }

                        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                        Log.i(TAG, "dequeue output buffer outputBufferIndex=" + outputBufferIndex);

                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // no output available yet
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            // not expected for an encoder
                            outputBuffers = mMediaCodec.getOutputBuffers();
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            //
                        } else if (outputBufferIndex < 0) {
                            // let's ignore it
                        } else {
                            ByteBuffer outputBuffer;

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);
                            } else {
                                outputBuffer = outputBuffers[outputBufferIndex];
                            }

                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            try {
                                boolean sync = false;

                                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {// sps
                                    sync = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;

                                    if (!sync) {
                                        byte[] temp = new byte[bufferInfo.size];
                                        outputBuffer.get(temp);
                                        mPpsSps = temp;
                                        continue;
                                    } else {
                                        mPpsSps = new byte[0];
                                    }
                                }

                                sync |= (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                                int len = mPpsSps.length + bufferInfo.size;

                                if (len > h264.length) {
                                    h264 = new byte[len];
                                }

                                if (sync) {
                                    System.arraycopy(mPpsSps, 0, h264, 0, mPpsSps.length);
                                    outputBuffer.get(h264, mPpsSps.length, bufferInfo.size);
                                    mEasyPusher.push(h264, 0, mPpsSps.length + bufferInfo.size, bufferInfo.presentationTimeUs / 1000, 2);

                                    if (BuildConfig.DEBUG)
                                        Log.i(TAG, String.format("push i video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                                } else {
                                    outputBuffer.get(h264, 0, bufferInfo.size);
                                    mEasyPusher.push(h264, 0, bufferInfo.size, bufferInfo.presentationTimeUs / 1000, 1);

                                    if (BuildConfig.DEBUG)
                                        Log.i(TAG, String.format("push video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                // 将缓冲器返回到编解码器
                                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    audioStream.removePusher(mEasyPusher);
                }
            }
        };

        mPushThread.start();
        startVirtualDisplay();
    }

    /**
     * 显示悬浮窗
     * */
    private void showView() {
        if (mLayout != null)
            return;

        mLayout = LayoutInflater.from(this).inflate(R.layout.float_btn, null);

        param = new WindowManager.LayoutParams();

        //设置type.系统提示型窗口，一般都在应用程序窗口之上.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            param.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            param.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        //设置效果为背景透明
        param.format = PixelFormat.RGBA_8888;

        //设置flags.不可聚焦及不可使用按钮对悬浮窗进行操控.
        param.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        param.alpha = 1.0f;

        //设置窗口初始停靠位置.
        param.gravity = Gravity.LEFT | Gravity.TOP;
//        param.x = getResources().getDisplayMetrics().widthPixels - param.width - getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        param.x = getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        param.y = getResources().getDimensionPixelSize(R.dimen.float_btn_height);

        /*
        * 设置悬浮窗口长宽数据。注意，这里的width和height均使用px而非dp.(如果你想完全对应布局设置，需要先获取到机器的dpi,px与dp的换算为px = dp * (dpi / 160))
        */
        param.width = getResources().getDimensionPixelSize(R.dimen.float_btn_width);
        param.height = getResources().getDimensionPixelSize(R.dimen.float_btn_height);

        // 添加mLayout
        mWindowManager.addView(mLayout, param);

        mGD = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            public boolean mScroll;

            int x;
            int y;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Intent intent = new Intent(RecordService.this, SplashActivity.class);
                intent.putExtra("screen-pushing", true);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                startActivity(intent);

                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!mScroll && Math.sqrt((e1.getX() - e2.getX()) * (e1.getX() - e2.getX()) + (e1.getY() - e2.getY()) * (e1.getY() - e2.getY())) > ViewConfiguration.get(RecordService.this).getScaledTouchSlop()) {
                    mScroll = true;
                }

                if (!mScroll) {
                    return false;
                } else {
                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                    p.x = (int) (x + e2.getRawX() - e1.getRawX());
                    p.y = (int) (y + e2.getRawY() - e1.getRawY());
                    mWindowManager.updateViewLayout(mLayout, p);

                    return true;
                }
            }

            @Override
            public boolean onDown(MotionEvent e) {
                if (mLayout == null)
                    return true;

                mScroll = false;

                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                x = p.x;
                y = p.y;

                return super.onDown(e);
            }
        });

        mLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGD.onTouchEvent(event);
            }
        });

        final TextView textView = mLayout.findViewById(R.id.text);
        textView.post(new Runnable() {
            @Override
            public void run() {
                textView.setText("" + SystemClock.elapsedRealtimeNanos());
                textView.postDelayed(this, 50);
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startVirtualDisplay() {
        if (mMpj == null) {
            mMpj = mMpmngr.getMediaProjection(StreamActivity.mResultCode, StreamActivity.mResultIntent);
            StreamActivity.mResultCode = 0;
            StreamActivity.mResultIntent = null;
        }

        if (mMpj == null)
            return;

        // 通过MediaProjection对象的createVirtualDisplay方法，拿到VirtureDisplay对象，拿这个对象的时候，需要把Surface对象传进去。
        mVirtualDisplay = mMpj.createVirtualDisplay(
                "record_screen",
                windowWidth,
                windowHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                mSurface,
                null,
                null);
    }

    /*
    * 销毁WindowManager
    * */
    private void hideView() {
        if (mLayout == null)
            return;

        mWindowManager.removeView(mLayout);
        mLayout = null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopPush() {
        Thread t = mPushThread;

        if (t != null) {
            mPushThread = null;

            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (mEasyPusher != null)
            mEasyPusher.stop();

        mEasyPusher = null;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void release() {
        Log.i(TAG, " release() ");

        if (mSurface != null) {
            mSurface.release();
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
    }

    @Subscribe
    public void onPushCallback(final PushCallback cb) {
        if (mLayout == null)
            return;

        mLayout.post(new Runnable() {
            @Override
            public void run() {
                switch (cb.code) {
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_FAILED:
                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_ABORT:
                        if (mLayout == null)
                            return;

                        mLayout.findViewById(R.id.action_push_error).setVisibility(View.VISIBLE);
                        break;
                    default:
                        if (mLayout == null)
                            return;
                        mLayout.findViewById(R.id.action_push_error).setVisibility(View.GONE);
                }
            }
        });
    }
}
