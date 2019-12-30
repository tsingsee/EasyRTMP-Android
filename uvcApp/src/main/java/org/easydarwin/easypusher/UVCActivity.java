package org.easydarwin.easypusher;

import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.easydarwin.easypusher.push.MediaStream;
import org.easydarwin.easypusher.util.RxHelper;
import org.easydarwin.easypusher.util.SurfaceTextureListenerWrapper;
import org.easydarwin.util.BUSUtil;

import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.functions.Consumer;

public class UVCActivity extends AppCompatActivity {
    private MediaStream mediaStream;

    private static final int REQUEST_CAMERA_PERMISSION = 1000;

    private Single<MediaStream> getMediaStream() {
        Single<MediaStream> single = RxHelper.single(MediaStream.getBindedMediaStream(this, this), mediaStream);
        if (mediaStream == null) {
            return single.doOnSuccess(new Consumer<MediaStream>() {
                @Override
                public void accept(MediaStream ms) throws Exception {
                    mediaStream = ms;
                }
            });
        } else {
            return single;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uvc);

        // 启动服务
        Intent intent = new Intent(this, MediaStream.class);
        startService(intent);

        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(final MediaStream ms) throws Exception {
                final TextView pushingStateText = findViewById(R.id.pushing_state);
                final TextView pushingBtn = findViewById(R.id.pushing);

                ms.observePushingState(UVCActivity.this, new Observer<MediaStream.PushingState>() {
                    @Override
                    public void onChanged(@Nullable MediaStream.PushingState pushingState) {
                        if (pushingState.screenPushing) {
                            pushingStateText.setText("屏幕推送");
                        } else {
                            pushingStateText.setText("推送");

                            if (pushingState.state > 0) {
                                pushingBtn.setText("停止");
                            } else {
                                pushingBtn.setText("推送");
                            }
                        }

                        pushingStateText.append(":\t" + pushingState.msg);
                        if (pushingState.state > 0) {
                            pushingStateText.append(pushingState.url);
                        }
                    }
                });

                TextureView textureView = findViewById(R.id.texture_view);
                textureView.setSurfaceTextureListener(new SurfaceTextureListenerWrapper() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                        ms.setSurfaceTexture(surfaceTexture);
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                        ms.setSurfaceTexture(null);
                        return true;
                    }
                });

                if (ActivityCompat.checkSelfPermission(UVCActivity.this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(UVCActivity.this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(UVCActivity.this, new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
                }
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                Toast.makeText(UVCActivity.this, "创建服务出错!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 权限获取到了
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    getMediaStream().subscribe(new Consumer<MediaStream>() {
                        @Override
                        public void accept(MediaStream mediaStream) throws Exception {
                            mediaStream.notifyPermissionGranted();
                        }
                    });
                } else {
                    // 没有获取到权限,退出....
                    Intent intent = new Intent(this, MediaStream.class);
                    stopService(intent);

                    finish();
                }

                break;
            }
        }
    }

    public void onPush(View view) {
        // 异步获取到MediaStream对象.
        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(final MediaStream mediaStream) throws Exception {
                // 判断当前的推送状态.
                MediaStream.PushingState state = mediaStream.getPushingState();
                if (state != null && state.state > 0) { // 当前正在推送,那终止推送和预览
                    mediaStream.stopStream();
                    mediaStream.closeCameraPreview();
                } else {
                    // switch 0表示后置,1表示前置,2表示UVC摄像头
                    RxHelper.single(mediaStream.switchCamera(2), null).subscribe(new Consumer<Object>() {
                        @Override
                        public void accept(Object o) throws Exception {
                            //String url = Config.getServerURL(UVCActivity.this);
                            String url = "rtmp://demo.easydss.com:3388/hls/mytest1?sign=OTMjcvBWg";
                            try {
                                mediaStream.startStream(url, code -> BUSUtil.BUS.post(new PushCallback(code)));
                            } catch (IOException e) {
                                e.printStackTrace();
                                Toast.makeText(UVCActivity.this, "激活失败，无效Key", Toast.LENGTH_LONG).show();
                            }
                        }
                    }, new Consumer<Throwable>() {
                        @Override
                        public void accept(final Throwable t) throws Exception {
                            t.printStackTrace();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(UVCActivity.this, "UVC摄像头启动失败.." + t.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                }
            }
        });
    }

    public void onQuit(View view) {     // 退出
        finish();

        // 终止服务
        Intent intent = new Intent(this, MediaStream.class);
        stopService(intent);
    }

    public void onBackground(View view) {   // 后台
        finish();
    }
}
