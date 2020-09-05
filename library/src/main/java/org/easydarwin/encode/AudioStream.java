package org.easydarwin.encode;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.push.Pusher;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * AudioRecord音频采集、MediaCodec硬编码
 * */
public class AudioStream {
    private static final String TAG = "AudioStream";

    private static AudioStream _this;

    private final Context context;

    EasyMuxer muxer;

    private int samplingRate = 8000;
    private int bitRate = 16000;
    private int BUFFER_SIZE = 1920;
    private boolean enableAudio;

    int mSamplingRateIndex = 0;

    AudioRecord mAudioRecord;   // 底层的音频采集
    MediaCodec mMediaCodec;     // 音频硬编码器

    private Thread mThread = null;

    protected MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    protected ByteBuffer[] outputBuffers = null; // 编码后的数据

    Set<Pusher> sets = new HashSet<>();

    /**
     * There are 13 supported frequencies by ADTS.
     **/
    public static final int[] AUDIO_SAMPLING_RATES = {
            96000, // 0
            88200, // 1
            64000, // 2
            48000, // 3
            44100, // 4
            32000, // 5
            24000, // 6
            22050, // 7
            16000, // 8
            12000, // 9
            11025, // 10
            8000, // 11
            7350, // 12
            -1, // 13
            -1, // 14
            -1, // 15
    };

    private Thread mWriter;
    private MediaFormat newFormat;

    public static synchronized AudioStream getInstance(Context context) {
        if (_this == null)
            _this = new AudioStream(context);

        return _this;
    }

    public AudioStream(Context context) {
        this.context = context;

        int i = 0;

        for (; i < AUDIO_SAMPLING_RATES.length; i++) {
            if (AUDIO_SAMPLING_RATES[i] == samplingRate) {
                mSamplingRateIndex = i;
                break;
            }
        }
    }

    /*
    * 添加推流器
    * */
    public void addPusher(Pusher pusher) {
        boolean shouldStart = false;

        synchronized (this) {
            if (sets.isEmpty())
                shouldStart = true;

            sets.add(pusher);
        }

        if (shouldStart)
            startRecord();
    }

    /*
    * 删除推流器
    * */
    public void removePusher(Pusher pusher){
        boolean shouldStop = false;

        synchronized (this){
            sets.remove(pusher);

            if (sets.isEmpty())
                shouldStop = true;
        }

        if (shouldStop)
            stop();
    }

    /*
    * 设置音频录像器
    * */
    public synchronized void setMuxer(EasyMuxer muxer) {
        if (muxer != null) {
            if (newFormat != null)
                muxer.addTrack(newFormat, false);
        }

        this.muxer = muxer;
    }

    /**
     * 编码
     */
    private void startRecord() {
        if (mThread != null)
            return;

        /**
         * 3、开启一个子线程，不断从AudioRecord的缓冲区将音频数据读出来。
         * 注意，这个过程一定要及时，否则就会出现“overrun”的错误，
         * 该错误在音频开发中比较常见，意味着应用层没有及时地取走音频数据，导致内部的音频缓冲区溢出。
         * */
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                int len, bufferIndex;

                try {
                    // 计算bufferSizeInBytes：int size = 采样率 x 位宽 x 通道数
                    int bufferSize = AudioRecord.getMinBufferSize(samplingRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);

                    /*
                    * 1、配置参数，初始化AudioRecord构造函数
                    * audioSource：音频采集的输入源，DEFAULT（默认），VOICE_RECOGNITION（用于语音识别，等同于DEFAULT），MIC（由手机麦克风输入），VOICE_COMMUNICATION（用于VoIP应用）等等
                    * sampleRateInHz：采样率，注意，目前44.1kHz是唯一可以保证兼容所有Android手机的采样率。
                    * channelConfig：通道数的配置，CHANNEL_IN_MONO（单通道），CHANNEL_IN_STEREO（双通道）
                    * audioFormat：配置“数据位宽”的,ENCODING_PCM_16BIT（16bit），ENCODING_PCM_8BIT（8bit）
                    * bufferSizeInBytes：配置的是 AudioRecord 内部的音频缓冲区的大小，该缓冲区的值不能低于一帧“音频帧”（Frame）的大小
                    * */
                    mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            samplingRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);

                    /*
                    * mp3为audio/mpeg, aac为audio/mp4a-latm, mp4为video/mp4v-es
                    * */
                    String encodeType = "audio/mp4a-latm";

                    // 初始化编码器
                    mMediaCodec = MediaCodec.createEncoderByType(encodeType);

                    MediaFormat format = new MediaFormat();
                    format.setString(MediaFormat.KEY_MIME, encodeType);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);// 比特率
                    format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);// 声道数
                    format.setInteger(MediaFormat.KEY_SAMPLE_RATE, samplingRate);// 采样率
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE); // 作用于inputBuffer的大小

                    mMediaCodec.configure(format,
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE);

                    mMediaCodec.start();

                    mWriter = new WriterThread();
                    mWriter.start();

                    // 2、开始采集
                    mAudioRecord.startRecording();

                    // 获取编码器的输入缓存inputBuffers
                    final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

                    long presentationTimeUs = 0;

                    while (mThread != null) {
                        // 从input缓冲区队列申请empty buffer
                        bufferIndex = mMediaCodec.dequeueInputBuffer(1000);

                        if (bufferIndex >= 0) {
                            inputBuffers[bufferIndex].clear();

                            /*
                            * 不断的读取采集到的声音数据，放进编码器的输入缓存inputBuffers中 进行编码
                            *   audioBuffer 存储写入音频录制数据的缓冲区。
                            *   sizeInBytes 请求的最大字节数。
                            * public int read (ByteBuffer audioBuffer, int sizeInBytes)
                            *  */
                            len = mAudioRecord.read(inputBuffers[bufferIndex], BUFFER_SIZE);

                            long timeUs = System.nanoTime() / 1000;
                            Log.i(TAG, String.format("audio: %d [%d] ", timeUs, timeUs - presentationTimeUs));
                            presentationTimeUs = timeUs;

                            // 将要编解码的数据拷贝到empty buffer，然后放入input缓冲区队列
                            if (len == AudioRecord.ERROR_INVALID_OPERATION || len == AudioRecord.ERROR_BAD_VALUE) {
                                mMediaCodec.queueInputBuffer(bufferIndex, 0, 0, presentationTimeUs, 0);
                            } else {
                                mMediaCodec.queueInputBuffer(bufferIndex, 0, len, presentationTimeUs, 0);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Record___Error!!!!!");
                    e.printStackTrace();
                } finally {
                    Thread t = mWriter;
                    mWriter = null;

                    while (t != null && t.isAlive()) {
                        try {
                            t.interrupt();
                            t.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    // 4、停止采集，释放资源。
                    if (mAudioRecord != null) {
                        mAudioRecord.stop();
                        mAudioRecord.release();
                        mAudioRecord = null;
                    }

                    // 停止编码
                    if (mMediaCodec != null) {
                        mMediaCodec.stop();
                        mMediaCodec.release();
                        mMediaCodec = null;
                    }
                }
            }
        }, "AACRecoder");

        if (enableAudio) {
            mThread.start();
        }
    }

    /**
     * 不断的从输出缓存中取出编码后的数据，然后push出去
     * */
    private class WriterThread extends Thread {
        public WriterThread() {
            super("WriteAudio");
        }

        @Override
        public void run() {
            int index;

            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                // 获取编码器的输出缓存outputBuffers
                outputBuffers = mMediaCodec.getOutputBuffers();
            }

            ByteBuffer mBuffer = ByteBuffer.allocate(10240);

            do {
                /*
                * 从output缓冲区队列申请编解码的buffer
                * BufferInfo：用于存储ByteBuffer的信息
                * TIMES_OUT：超时时间（在一个单独的线程专门取输出数据，为了避免CPU资源的浪费，需设置合适的值）
                * */
                index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 10000);

                if (index >= 0) {
                    if (mBufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                        continue;
                    }

                    mBuffer.clear();
                    ByteBuffer outputBuffer;

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        outputBuffer = mMediaCodec.getOutputBuffer(index);
                    } else {
                        outputBuffer = outputBuffers[index];
                    }

                    // 合成音频
                    if (muxer != null)
                        muxer.pumpStream(outputBuffer, mBufferInfo, false);

                    outputBuffer.get(mBuffer.array(), 7, mBufferInfo.size);
                    outputBuffer.clear();

                    mBuffer.position(7 + mBufferInfo.size);
                    addADTStoPacket(mBuffer.array(), mBufferInfo.size + 7);
                    mBuffer.flip();
                    Collection<Pusher> p;

                    synchronized (AudioStream.this) {
                        p = sets;
                    }

                    Iterator<Pusher> it = p.iterator();

                    // 推流
                    while (it.hasNext()) {
                        Pusher ps = it.next();
                        ps.push(mBuffer.array(),
                                0,
                                mBufferInfo.size + 7,
                                mBufferInfo.presentationTimeUs / 1000,
                                0);
                    }

                    // 处理完上面的步骤后再将该buffer放回到output缓冲区队列
                    mMediaCodec.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = mMediaCodec.getOutputBuffers();
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (AudioStream.this) {
                        Log.v(TAG, "output format changed...");
                        newFormat = mMediaCodec.getOutputFormat();

                        if (muxer != null)
                            muxer.addTrack(newFormat, false);
                    }
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.v(TAG, "No buffer available...");
                } else {
                    Log.e(TAG, "Message: " + index);
                }
            } while (mWriter != null);
        }
    }

    /**
     * 添加ADTS头
     *
     * @param packet
     * @param packetLen
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1;
        packet[2] = (byte) (((2 - 1) << 6) + (mSamplingRateIndex << 2) + (1 >> 2));
        packet[3] = (byte) (((1 & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    private void stop() {
        try {
            Thread t = mThread;
            mThread = null;

            if (t != null) {
                t.interrupt();
                t.join();
            }
        } catch (InterruptedException e) {
            e.fillInStackTrace();
        }
    }

    public void setEnableAudio(boolean enableAudio) {
        this.enableAudio = enableAudio;
    }
}
