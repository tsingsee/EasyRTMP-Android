package org.easydarwin.easypusher.push;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.UVCCamera;

import org.easydarwin.bus.CameraId;
import org.easydarwin.bus.SupportResolution;
import org.easydarwin.easypusher.BackgroundCameraService;
import org.easydarwin.easypusher.EasyApplication;
import org.easydarwin.easypusher.UVCCameraService;
import org.easydarwin.easypusher.util.SPUtil;
import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.encode.AudioStream;
import org.easydarwin.encode.ClippableVideoConsumer;
import org.easydarwin.encode.HWConsumer;
import org.easydarwin.encode.SWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.muxer.RecordVideoConsumer;
import org.easydarwin.push.InitCallback;
import org.easydarwin.push.Pusher;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.util.BUSUtil;
import org.easydarwin.util.Util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar;
import static org.easydarwin.easypusher.BuildConfig.RTMP_KEY;

/**
 * 摄像头实时数据采集，并调用相关编码器
 * */
public class MediaStream {
    private static final String TAG = MediaStream.class.getSimpleName();
    private static final int SWITCH_CAMERA = 11;

    private final boolean enableVideo;
    private boolean mSWCodec, mHevc;    // mSWCodec是否软编码, mHevc是否H265

    private String recordPath;          // 录像地址
    boolean isPushStream = false;       // 是否要推送数据
    private int displayRotationDegree;  // 旋转角度

    private Context context;
    WeakReference<SurfaceTexture> mSurfaceHolderRef;

    private VideoConsumer mVC, mRecordVC;
    private AudioStream audioStream;
    private EasyMuxer mMuxer;
    private Pusher mEasyPusher;

    private final HandlerThread mCameraThread;
    private final Handler mCameraHandler;

    /**
     * 初始化MediaStream
     * */
    public MediaStream(Context context, SurfaceTexture texture, boolean enableVideo) {
        this.context = context;
        audioStream = AudioStream.getInstance(context);
        mSurfaceHolderRef = new WeakReference(texture);

        mCameraThread = new HandlerThread("CAMERA") {
            public void run() {
                try {
                    super.run();
                } catch (Throwable e) {
                    e.printStackTrace();

                    Intent intent = new Intent(context, BackgroundCameraService.class);
                    context.stopService(intent);
                } finally {
                    stopStream();
                    stopPreview();
                    destroyCamera();
                }
            }
        };

        mCameraThread.start();

        mCameraHandler = new Handler(mCameraThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);

                if (msg.what == SWITCH_CAMERA) {
                    switchCameraTask.run();
                }
            }
        };

        this.enableVideo = enableVideo;
    }

    /// 初始化摄像头
    public void createCamera() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                createCamera();
            });

            return;
        }

        mSWCodec = SPUtil.getswCodec(context);
        mHevc = SPUtil.getHevcCodec(context);
        mEasyPusher = new EasyRTMP(mHevc ? EasyRTMP.VIDEO_CODEC_H265 : EasyRTMP.VIDEO_CODEC_H264, RTMP_KEY);

        if (!enableVideo) {
            return;
        }

        if (mCameraId == CAMERA_FACING_BACK_UVC) {
            createUvcCamera();
        } else {
            createNativeCamera();
        }
    }

    private void createNativeCamera() {
        try {
            mCamera = Camera.open(mCameraId);// 初始化创建Camera实例对象
            mCamera.setErrorCallback((i, camera) -> {
                throw new IllegalStateException("Camera Error:" + i);
            });
            Log.i(TAG, "open Camera");

            parameters = mCamera.getParameters();

            if (Util.getSupportResolution(context).size() == 0) {
                StringBuilder stringBuilder = new StringBuilder();

                // 查看支持的预览尺寸
                List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();

                for (Camera.Size str : supportedPreviewSizes) {
                    stringBuilder.append(str.width + "x" + str.height).append(";");
                }

                Util.saveSupportResolution(context, stringBuilder.toString());
            }

            BUSUtil.BUS.post(new SupportResolution());

            camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, camInfo);
            int cameraRotationOffset = camInfo.orientation;

            if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT)
                cameraRotationOffset += 180;

            int rotate = (360 + cameraRotationOffset - displayRotationDegree) % 360;
            parameters.setRotation(rotate); // 设置Camera预览方向
//            parameters.setRecordingHint(true);

            ArrayList<CodecInfo> infos = listEncoders(mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);

            if (!infos.isEmpty()) {
                CodecInfo ci = infos.get(0);
                info.mName = ci.mName;
                info.mColorFormat = ci.mColorFormat;
            } else {
                mSWCodec = true;
            }

//            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            parameters.setPreviewSize(defaultWidth, defaultHeight);// 设置预览尺寸

            int[] ints = determineMaximumSupportedFramerate(parameters);
            parameters.setPreviewFpsRange(ints[0], ints[1]);

            List<String> supportedFocusModes = parameters.getSupportedFocusModes();

            if (supportedFocusModes == null)
                supportedFocusModes = new ArrayList<>();

            // 自动对焦
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

//            int maxExposureCompensation = parameters.getMaxExposureCompensation();
//            parameters.setExposureCompensation(3);
//
//            if(parameters.isAutoExposureLockSupported()) {
//                parameters.setAutoExposureLock(false);
//            }

//            parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
//            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
//            parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
//            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
//            mCamera.setFaceDetectionListener(new );

//            if (parameters.isAutoWhiteBalanceLockSupported()){
//                parameters.setAutoExposureLock(false);
//            }

            mCamera.setParameters(parameters);
            Log.i(TAG, "setParameters");

            int displayRotation;
            displayRotation = (cameraRotationOffset - displayRotationDegree + 360) % 360;
            mCamera.setDisplayOrientation(displayRotation);

            Log.i(TAG, "setDisplayOrientation");
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

//            String stack = sw.toString();
            destroyCamera();
            e.printStackTrace();
        }
    }

    private void createUvcCamera() {
//        frameWidth = frameRotate ? height : width;
//        frameHeight = frameRotate ? width : height;

        ArrayList<CodecInfo> infos = listEncoders(mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);

        if (!infos.isEmpty()) {
            CodecInfo ci = infos.get(0);
            info.mName = ci.mName;
            info.mColorFormat = ci.mColorFormat;
        } else {
            mSWCodec = true;
        }

        frameWidth = defaultWidth;
        frameHeight = defaultHeight;

        uvcCamera = UVCCameraService.liveData.getValue();
        if (uvcCamera != null) {
            uvcCamera.setPreviewSize(frameWidth,
                    frameHeight,
                    1,
                    30,
                    UVCCamera.PIXEL_FORMAT_YUV420SP,1.0f);
        }

        if (uvcCamera == null) {
            mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
            createNativeCamera();
        }
    }

    /// 销毁Camera
    public synchronized void destroyCamera() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> destroyCamera());
            return;
        }

        if (uvcCamera != null) {
            uvcCamera.destroy();
            uvcCamera = null;
        }

        if (mCamera != null) {
            mCamera.stopPreview();

            try {
                mCamera.release();
            } catch (Exception e) {
                e.printStackTrace();
            }

            Log.i(TAG, "release Camera");

            mCamera = null;
        }

        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    /// 回收线程
    public void release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mCameraThread.quitSafely();
        } else {
            if (!mCameraHandler.post(() -> mCameraThread.quit())) {
                mCameraThread.quit();
            }
        }

        try {
            mCameraThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /// 开启预览
    public synchronized void startPreview() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> startPreview());
            return;
        }

        if (uvcCamera != null) {
            startUvcPreview();
        } else if (mCamera != null) {
            startCameraPreview();
        }

        if (mSWCodec) {
            SWConsumer sw = new SWConsumer(context, mEasyPusher, SPUtil.getBitrateKbps(context));
            mVC = new ClippableVideoConsumer(context,
                    sw,
                    frameWidth,
                    frameHeight,
                    SPUtil.getEnableVideoOverlay(context));
        } else {
            HWConsumer hw = new HWConsumer(context,
                    mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC,
                    mEasyPusher,
                    SPUtil.getBitrateKbps(context),
                    info.mName,
                    info.mColorFormat);
            mVC = new ClippableVideoConsumer(context,
                    hw,
                    frameWidth,
                    frameHeight,
                    SPUtil.getEnableVideoOverlay(context));
        }

        if (uvcCamera != null || mCamera != null) {
            mVC.onVideoStart(frameWidth, frameHeight);
        }

        audioStream.setEnableAudio(SPUtil.getEnableAudio(context));
        audioStream.addPusher(mEasyPusher);
    }

    private void startUvcPreview() {
        SurfaceTexture holder = mSurfaceHolderRef.get();
        if (holder != null) {
            uvcCamera.setPreviewTexture(holder);
        }

        try {
            uvcCamera.setFrameCallback(uvcFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP/*UVCCamera.PIXEL_FORMAT_NV21*/);
            uvcCamera.startPreview();
        } catch (Throwable e){
            e.printStackTrace();
        }
    }

    private void startCameraPreview() {
        int previewFormat = parameters.getPreviewFormat();

        Camera.Size previewSize = parameters.getPreviewSize();
        int size = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(previewFormat) / 8;

        defaultWidth = previewSize.width;
        defaultHeight = previewSize.height;

        mCamera.addCallbackBuffer(new byte[size]);
        mCamera.addCallbackBuffer(new byte[size]);
        mCamera.setPreviewCallbackWithBuffer(previewCallback);

        Log.i(TAG, "setPreviewCallbackWithBuffer");

        try {
            // TextureView的
            SurfaceTexture holder = mSurfaceHolderRef.get();

            // SurfaceView传入上面创建的Camera对象
            if (holder != null) {
                mCamera.setPreviewTexture(holder);
                Log.i(TAG, "setPreviewTexture");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        mCamera.startPreview();

        boolean frameRotate;
        int result;

        if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (camInfo.orientation + displayRotationDegree) % 360;
        } else {  // back-facing
            result = (camInfo.orientation - displayRotationDegree + 360) % 360;
        }

        frameRotate = result % 180 != 0;

        frameWidth = frameRotate ? defaultHeight : defaultWidth;
        frameHeight = frameRotate ? defaultWidth : defaultHeight;
    }

    /// 停止预览
    public synchronized void stopPreview() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> stopPreview());
            return;
        }

        if (uvcCamera != null) {
            uvcCamera.stopPreview();
        }

//        mCameraHandler.removeCallbacks(dequeueRunnable);

        // 关闭摄像头
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
            Log.i(TAG, "StopPreview");
        }

        // 关闭音频采集和音频编码器
        if (audioStream != null) {
            audioStream.removePusher(mEasyPusher);
            audioStream.setMuxer(null);
            Log.i(TAG, "Stop AudioStream");
        }

        // 关闭视频编码器
        if (mVC != null) {
            mVC.onVideoStop();
            Log.i(TAG, "Stop VC");
        }

        // 关闭录像的编码器
        if (mRecordVC != null) {
            mRecordVC.onVideoStop();
        }

        // 关闭音视频合成器
        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    /// 开始推流
    public void startStream(String url, InitCallback callback) throws IOException {
        try {
            if (SPUtil.getEnableVideo(EasyApplication.getEasyApplication()))
                mEasyPusher.initPush(url, context, callback);
            else
                mEasyPusher.initPush(url, context, callback, ~0);

            isPushStream = true;
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new IOException(ex.getMessage());
        }
    }

    /// 停止推流
    public void stopStream() {
        mEasyPusher.stop();
        isPushStream = false;
    }

    /// 开始录像
    public synchronized void startRecord() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> startRecord());
            return;
        }

        if (mCamera == null && uvcCamera == null) {
            return;
        }

        // 默认录像时间300000毫秒
        mMuxer = new EasyMuxer(new File(recordPath, new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date())).toString(), 300000);

        mRecordVC = new RecordVideoConsumer(context,
                mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC,
                mMuxer,
                SPUtil.getEnableVideoOverlay(context),
                SPUtil.getBitrateKbps(context),
                info.mName,
                info.mColorFormat);
        mRecordVC.onVideoStart(frameWidth, frameHeight);

        if (audioStream != null) {
            audioStream.setMuxer(mMuxer);
        }
    }

    /// 停止录像
    public synchronized void stopRecord() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> stopRecord());
            return;
        }

        if (mRecordVC == null || audioStream == null) {
//            nothing
        } else {
            audioStream.setMuxer(null);
            mRecordVC.onVideoStop();
            mRecordVC = null;
        }

        if (mMuxer != null)
            mMuxer.release();

        mMuxer = null;
    }

    /// 更新分辨率
    public void updateResolution(final int w, final int h) {
        if (mCamera == null)
            return;

        stopPreview();
        destroyCamera();

        mCameraHandler.post(() -> {
            defaultWidth = w;
            defaultHeight = h;
        });

        createCamera();
        startPreview();
    }

    /* ============================== Camera ============================== */

    /*
     * 默认后置摄像头
     *   Camera.CameraInfo.CAMERA_FACING_BACK
     *   Camera.CameraInfo.CAMERA_FACING_FRONT
     *   CAMERA_FACING_BACK_UVC
     * */
    int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    public static final int CAMERA_FACING_BACK_UVC = 2;
    public static final int CAMERA_FACING_BACK_LOOP = -1;

    private int frameWidth;
    private int frameHeight;
    int defaultWidth = 1280, defaultHeight = 720;
    private int mTargetCameraId;

    /**
     * 切换前后摄像头
     *  CAMERA_FACING_BACK_LOOP                 循环切换摄像头
     *  Camera.CameraInfo.CAMERA_FACING_BACK    后置摄像头
     *  Camera.CameraInfo.CAMERA_FACING_FRONT   前置摄像头
     *  CAMERA_FACING_BACK_UVC                  UVC摄像头
     * */
    public void switchCamera(int cameraId) {
        this.mTargetCameraId = cameraId;

        if (mCameraHandler.hasMessages(SWITCH_CAMERA)) {
            return;
        } else {
            mCameraHandler.sendEmptyMessage(SWITCH_CAMERA);
        }
    }

    public void switchCamera() {
        switchCamera(CAMERA_FACING_BACK_LOOP);
    }

    /// 切换摄像头的线程
    private Runnable switchCameraTask = new Runnable() {
        @Override
        public void run() {
            if (!enableVideo)
                return;

            try {
                if (mTargetCameraId != CAMERA_FACING_BACK_LOOP && mCameraId == mTargetCameraId) {
                    if (uvcCamera != null || mCamera != null) {
                        return;
                    }
                }

                if (mTargetCameraId == CAMERA_FACING_BACK_LOOP) {
                    if (mCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
                    } else if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        mCameraId = CAMERA_FACING_BACK_UVC;// 尝试切换到外置摄像头
                    } else {
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
                    }
                } else {
                    mCameraId = mTargetCameraId;
                }

                BUSUtil.BUS.post(new CameraId(mCameraId));

                stopPreview();
                destroyCamera();
                createCamera();
                startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {

            }
        }
    };

    /* ============================== Native Camera ============================== */

    Camera mCamera;
    private Camera.CameraInfo camInfo;
    private Camera.Parameters parameters;
    private byte[] i420_buffer;

    // 摄像头预览的视频流数据
    Camera.PreviewCallback previewCallback = (data, camera) -> {
        if (data == null)
            return;

        int result;
        if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (camInfo.orientation + displayRotationDegree) % 360;
        } else {  // back-facing
            result = (camInfo.orientation - displayRotationDegree + 360) % 360;
        }

        if (i420_buffer == null || i420_buffer.length != data.length) {
            i420_buffer = new byte[data.length];
        }

        JNIUtil.ConvertToI420(data, i420_buffer, defaultWidth, defaultHeight, 0, 0, defaultWidth, defaultHeight, result % 360, 2);
        System.arraycopy(i420_buffer, 0, data, 0, data.length);

        if (mRecordVC != null) {
            mRecordVC.onVideo(i420_buffer, 0);
        }

        mVC.onVideo(data, 0);
        mCamera.addCallbackBuffer(data);
    };

    /* ============================== UVC Camera ============================== */

    private UVCCamera uvcCamera;

    BlockingQueue<byte[]> cache = new ArrayBlockingQueue<byte[]>(100);
//    BlockingQueue<byte[]>
//    = new ArrayBlockingQueue<byte[]>(10);
//    final Runnable dequeueRunnable = new Runnable() {
//        @Override
//        public void run() {
//            try {
//                byte[] data = bufferQueue.poll(10, TimeUnit.MICROSECONDS);
//
//                if (data != null) {
//                    onPreviewFrame2(data, uvcCamera);
//                    cache.offer(data);
//                }
//
//                if (uvcCamera == null)
//                    return;
//
//                mCameraHandler.post(this);
//            } catch (InterruptedException ex) {
//                ex.printStackTrace();
//            }
//        }
//    };

    final IFrameCallback uvcFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            if (uvcCamera == null)
                return;

            Thread.currentThread().setName("UVCCamera");
            frame.clear();

            byte[] data = cache.poll();
            if (data == null) {
                data = new byte[frame.capacity()];
            }

            frame.get(data);

//            bufferQueue.offer(data);
//            mCameraHandler.post(dequeueRunnable);

            onPreviewFrame2(data, uvcCamera);
        }
    };

    public void onPreviewFrame2(byte[] data, Object camera) {
        if (data == null)
            return;

        if (i420_buffer == null || i420_buffer.length != data.length) {
            i420_buffer = new byte[data.length];
        }

        JNIUtil.ConvertToI420(data, i420_buffer,
                defaultWidth, defaultHeight,
                0, 0,
                defaultWidth, defaultHeight,
                0, 2);
        System.arraycopy(i420_buffer, 0, data, 0, data.length);

        if (mRecordVC != null) {
            mRecordVC.onVideo(i420_buffer, 0);
        }

        mVC.onVideo(data, 0);
    }

    /* ============================== CodecInfo ============================== */

    public static CodecInfo info = new CodecInfo();

    public static class CodecInfo {
        public String mName;
        public int mColorFormat;
    }

    public static ArrayList<CodecInfo> listEncoders(String mime) {
        // 可能有多个编码库，都获取一下
        ArrayList<CodecInfo> codecInfoList = new ArrayList<>();
        int numCodecs = MediaCodecList.getCodecCount();

        // int colorFormat = 0;
        // String name = null;
        for (int i1 = 0; i1 < numCodecs; i1++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i1);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            if (codecMatch(mime, codecInfo)) {
                String name = codecInfo.getName();
                int colorFormat = getColorFormat(codecInfo, mime);

                if (colorFormat != 0) {
                    CodecInfo ci = new CodecInfo();
                    ci.mName = name;
                    ci.mColorFormat = colorFormat;
                    codecInfoList.add(ci);
                }
            }
        }

        return codecInfoList;
    }

    /* ============================== private method ============================== */

    private static boolean codecMatch(String mimeType, MediaCodecInfo codecInfo) {
        String[] types = codecInfo.getSupportedTypes();

        for (String type : types) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }

        return false;
    }

    private static int getColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        // 在ByteBuffer模式下，视频缓冲区根据其颜色格式进行布局。
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int[] cf = new int[capabilities.colorFormats.length];
        System.arraycopy(capabilities.colorFormats, 0, cf, 0, cf.length);
        List<Integer> sets = new ArrayList<>();

        for (int i = 0; i < cf.length; i++) {
            sets.add(cf[i]);
        }

        if (sets.contains(COLOR_FormatYUV420SemiPlanar)) {
            return COLOR_FormatYUV420SemiPlanar;
        } else if (sets.contains(COLOR_FormatYUV420Planar)) {
            return COLOR_FormatYUV420Planar;
        } else if (sets.contains(COLOR_FormatYUV420PackedPlanar)) {
            return COLOR_FormatYUV420PackedPlanar;
        } else if (sets.contains(COLOR_TI_FormatYUV420PackedSemiPlanar)) {
            return COLOR_TI_FormatYUV420PackedSemiPlanar;
        }

        return 0;
    }

    private static int[] determineMaximumSupportedFramerate(Camera.Parameters parameters) {
        int[] maxFps = new int[]{0, 0};
        List<int[]> supportedFpsRanges = parameters.getSupportedPreviewFpsRange();

        for (Iterator<int[]> it = supportedFpsRanges.iterator(); it.hasNext(); ) {
            int[] interval = it.next();

            if (interval[1] > maxFps[1] || (interval[0] > maxFps[0] && interval[1] == maxFps[1])) {
                maxFps = interval;
            }
        }

        return maxFps;
    }

    /* ============================== get/set ============================== */

    public void setRecordPath(String recordPath) {
        this.recordPath = recordPath;
    }

    public boolean isRecording() {
        return mMuxer != null;
    }

    public void setSurfaceTexture(SurfaceTexture texture) {
        mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
    }

    public boolean isStreaming() {
        return isPushStream;
    }

    public Camera getCamera() {
        return mCamera;
    }

    public int getDisplayRotationDegree() {
        return displayRotationDegree;
    }

    public void setDisplayRotationDegree(int degree) {
        displayRotationDegree = degree;
    }

    /**
     * 旋转YUV格式数据
     *
     * @param src    YUV数据
     * @param format 0，420P；1，420SP
     * @param width  宽度
     * @param height 高度
     * @param degree 旋转度数
     */
    private static void yuvRotate(byte[] src, int format, int width, int height, int degree) {
        int offset = 0;
        if (format == 0) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += (width * height);
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
            offset += width * height / 4;
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
        } else if (format == 1) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += width * height;
            JNIUtil.rotateShortMatrix(src, offset, width / 2, height / 2, degree);
        }
    }
}