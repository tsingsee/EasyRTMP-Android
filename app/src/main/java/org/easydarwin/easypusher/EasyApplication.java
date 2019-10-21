package org.easydarwin.easypusher;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.res.AssetManager;
import android.os.Build;

import com.tencent.bugly.Bugly;
import com.tencent.bugly.beta.Beta;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.easydarwin.easyrtmp.push.EasyRTMP.getActiveDays;

public class EasyApplication extends Application {

    public static final String CHANNEL_CAMERA = "camera";

    private static EasyApplication mApplication;
    public static int activeDays = 9999;

    public static EasyApplication getEasyApplication() {
        return mApplication;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mApplication = this;

        Bugly.init(getApplicationContext(), "9a829a728a", false);
        setBuglyInit();

        File youyuan = getFileStreamPath("SIMYOU.ttf");
        if (!youyuan.exists()) {
            AssetManager am = getAssets();

            try {
                InputStream is = am.open("zk/SIMYOU.ttf");
                FileOutputStream os = openFileOutput("SIMYOU.ttf", MODE_PRIVATE);
                byte[] buffer = new byte[1024];
                int len;

                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }

                os.close();
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        activeDays = getActiveDays(this,BuildConfig.RTMP_KEY);

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.camera);

            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_CAMERA, name, importance);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void setBuglyInit(){
        // 添加可显示弹窗的Activity
        Beta.canShowUpgradeActs.add(StreamActivity.class);

//        例如，只允许在MainActivity上显示更新弹窗，其他activity上不显示弹窗; 如果不设置默认所有activity都可以显示弹窗。
//        设置是否显示消息通知
        Beta.enableNotification = true;

//        如果你不想在通知栏显示下载进度，你可以将这个接口设置为false，默认值为true。
//        设置Wifi下自动下载
        Beta.autoDownloadOnWifi = false;

//        如果你想在Wifi网络下自动下载，可以将这个接口设置为true，默认值为false。
//        设置是否显示弹窗中的apk信息
        Beta.canShowApkInfo = true;

//        如果你使用我们默认弹窗是会显示apk信息的，如果你不想显示可以将这个接口设置为false。
//        关闭热更新能力
        Beta.enableHotfix = true;

        Beta.largeIconId = R.mipmap.ic_launcher_foreground;
        Beta.smallIconId = R.mipmap.ic_launcher_foreground;

        // 设置是否显示消息通知
        Beta.enableNotification = true;
    }
}
