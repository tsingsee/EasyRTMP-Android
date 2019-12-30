/*
	Copyright (c) 2013-2016 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/

package org.easydarwin.easypusher.util;

import android.content.Context;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.TextUtils;

/**
 * 推流地址的常量类
 */
public class Config {

    private static final String SERVER_URL = "serverUrl";
    private static final String DEFAULT_SERVER_URL = "rtmp://demo.easydss.com:10085/live/stream_"+String.valueOf((int) (Math.random() * 1000000 + 100000));

    public static String getServerURL(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(SERVER_URL, DEFAULT_SERVER_URL);
    }

    public static void setServerURL(Context context, String value) {
        if (value == null || TextUtils.isEmpty(value)) {
            value = DEFAULT_SERVER_URL;
        }

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(SERVER_URL, value)
                .apply();
    }

    public static String recordPath() {
        return Environment.getExternalStorageDirectory() +"/EasyRTMP";
    }
}
