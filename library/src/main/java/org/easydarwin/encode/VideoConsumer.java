package org.easydarwin.encode;

import org.easydarwin.muxer.EasyMuxer;

/**
 * Created by apple on 2017/5/13.
 */
public interface VideoConsumer {
    void onVideoStart(int width, int height) ;

    /*
    * 传入原始视频数据，编码
    * */
    int onVideo(byte[] data, int format);

    void onVideoStop();

    /*
    * 添加视频合成器
    * */
    void setMuxer(EasyMuxer muxer);
}
