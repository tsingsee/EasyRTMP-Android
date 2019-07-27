package org.easydarwin.muxer;

import android.content.Context;

import org.easydarwin.encode.HWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.sw.TxtOverlay;

import java.text.SimpleDateFormat;
import java.util.Date;

public class RecordVideoConsumer implements VideoConsumer {

    VideoConsumer consumer;

    private TxtOverlay overlay;
    private final Context context;
    private boolean enableVideoOverlay;

    public RecordVideoConsumer(Context context,
                               String mime,
                               EasyMuxer muxer,
                               boolean enableVideoOverlay,
                               int bitrateKbps,
                               String mName,
                               int mColorFormat) {

        this.context = context;
        this.enableVideoOverlay = enableVideoOverlay;

        consumer = new HWConsumer(context, mime, null, bitrateKbps, mName, mColorFormat);
        consumer.setMuxer(muxer);
    }

    @Override
    public void onVideoStart(int width, int height) {
        consumer.onVideoStart(width, height);
        overlay = new TxtOverlay(context);
        overlay.init(width, height, context.getFileStreamPath("SIMYOU.ttf").getPath());
    }

    @Override
    public int onVideo(byte[] data, int format) {
        if (enableVideoOverlay) {
            String txt = new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
            overlay.overlay(data, txt);
        }

        return consumer.onVideo(data, format);
    }

    @Override
    public void onVideoStop() {
        consumer.onVideoStop();
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
    }

    @Override
    public void setMuxer(EasyMuxer muxer) {

    }
}
