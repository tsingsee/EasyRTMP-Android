/*
	Copyright (c) 2012-2018 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/

package org.easydarwin.easyrtmp.push;

import android.content.Context;
import android.util.Log;

import org.easydarwin.bus.StreamStat;
import org.easydarwin.push.InitCallback;
import org.easydarwin.push.Pusher;
import org.easydarwin.util.BUSUtil;

public class EasyRTMP implements Pusher {
    private static String TAG = "EasyRTMP";
    private static String RTMP_KEY;

    static {
        System.loadLibrary("easyrtmp");
    }

    private long pPreviewTS;
    private long mTotal;
    private int mTotalFrms;

    /* 视频编码 */
    public static final int VIDEO_CODEC_H264 = 0;   /* H264  */
    public static final int VIDEO_CODEC_H265 = 1;   /* 1211250229 */
    private final int videoCodec;

    private long mPusherObj = 0;

    public EasyRTMP(int videoCodec, String key) {
        this.videoCodec = videoCodec;
        this.RTMP_KEY = key;
    }

    public native static int getActiveDays(Context context,String key);

    /**
     * 初始化
     *
     * @param url RTMP服务器地址
     * @param key 授权码
     */
    public native long init(String url, String key, Context context, OnInitPusherCallback callback, int videoCodec, int fps, int audiosamplerate, int audiochannelcount);

    public synchronized void initPush(final String url, final Context context, final InitCallback callback) {
        initPush(url, context, callback, 25);
    }

    /**
     * 推送编码后的H264数据
     *
     * @param data      H264数据
     * @param timestamp 时间戳，毫秒
     */
    private native void push(long pusherObj, byte[] data, int offset, int length, long timestamp, int type);

    /**
     * 停止推送
     */
    private native void stopPush(long pusherObj);

    public synchronized void stop() {
        if (mPusherObj == 0)
            return;

        Log.d(TAG, "stopPush begin");
        stopPush(mPusherObj);
        Log.d(TAG, "stopPush end");

        mPusherObj = 0;
    }

    @Override
    public void initPush(String serverIP, String serverPort, String streamName, Context context, InitCallback callback) {
        throw new RuntimeException("not support");
    }

    @Override
    public synchronized void initPush(final String url, final Context context, final InitCallback callback, int fps) throws IllegalStateException {
        Log.d(TAG, "startPush begin");

        mPusherObj = init(url, RTMP_KEY, context, new OnInitPusherCallback() {
            int code = Integer.MAX_VALUE;

            @Override
            public void onCallback(int code) {
                if (code != this.code) {
                    this.code = code;

                    if (callback != null)
                        callback.onCallback(code);
                }
            }}, videoCodec, fps, 8000, 1);

        Log.d(TAG, "start push end");
    }

    public void push(byte[] data, long timestamp, int type) {
        push(data, 0, data.length, timestamp, type);
    }

    public synchronized void push(byte[] data, int offset, int length, long timestamp, int type) {
        mTotal += length;

        if (type == 1) {
            mTotalFrms++;
        }

        long interval = System.currentTimeMillis() - pPreviewTS;

        if (interval >= 3000) {
            long bps = mTotal * 1000 / (interval);
            long fps = mTotalFrms * 1000 / (interval);

            Log.i(TAG, String.format("bytesPerSecond:%d, framePerSecond:%d", fps, bps));

            pPreviewTS = System.currentTimeMillis();
            mTotal = 0;
            mTotalFrms = 0;

            BUSUtil.BUS.post(new StreamStat((int) fps, (int) bps));
        }

        if (mPusherObj == 0)
            return;

        Log.d(TAG, "push " + type + " time: " + timestamp);
        push(mPusherObj, data, offset, length, timestamp, type);
    }

    public interface OnInitPusherCallback {
        void onCallback(int code);

        static class CODE {
            public static final int EASY_ACTIVATE_INVALID_KEY = -1;             //无效Key
            public static final int EASY_ACTIVATE_TIME_ERR = -2;                //时间错误
            public static final int EASY_ACTIVATE_PROCESS_NAME_LEN_ERR = -3;    //进程名称长度不匹配
            public static final int EASY_ACTIVATE_PROCESS_NAME_ERR = -4;        //进程名称不匹配
            public static final int EASY_ACTIVATE_VALIDITY_PERIOD_ERR = -5;     //有效期校验不一致
            public static final int EASY_ACTIVATE_PLATFORM_ERR = -6;            //平台不匹配
            public static final int EASY_ACTIVATE_COMPANY_ID_LEN_ERR = -7;      //授权使用商不匹配
            public static final int EASY_ACTIVATE_SUCCESS = 0;                  //激活成功
            public static final int EASY_RTMP_STATE_CONNECTING = 1;             //连接中
            public static final int EASY_RTMP_STATE_CONNECTED = 2;              //连接成功
            public static final int EASY_RTMP_STATE_CONNECT_FAILED = 3;         //连接失败
            public static final int EASY_RTMP_STATE_CONNECT_ABORT = 4;          //连接异常中断
            public static final int EASY_RTMP_STATE_PUSHING = 5;                //推流中
            public static final int EASY_RTMP_STATE_DISCONNECTED = 6;           //断开连接
            public static final int EASY_RTMP_STATE_ERROR = 7;
        }
    }
}
