package org.easydarwin.easypusher;

import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;

import org.easydarwin.easypusher.databinding.ActivityAboutBinding;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAboutBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_about);
        binding.version.setText(getString(R.string.app_name) + "-" + BuildConfig.VERSION_NAME);
        binding.serverTitle.setText("-EasyDSS RTMP流媒体服务器：\n");
        binding.serverTitle.setMovementMethod(LinkMovementMethod.getInstance());
        SpannableString
                spannableString = new SpannableString("http://www.easydss.com");
        //设置下划线文字
        spannableString.setSpan(new URLSpan("http://www.easydss.com"), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        //设置文字的前景色
        spannableString.setSpan(new ForegroundColorSpan(Color.RED), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        binding.serverTitle.append(spannableString);


        binding.playerTitle.setText("-EasyPlayerPro全功能播放器：\n");
        binding.playerTitle.setMovementMethod(LinkMovementMethod.getInstance());
        spannableString = new SpannableString("https://github.com/EasyDSS/EasyPlayerPro");
        //设置下划线文字
        spannableString.setSpan(new URLSpan("https://github.com/EasyDSS/EasyPlayerPro"), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        //设置文字的前景色
        spannableString.setSpan(new ForegroundColorSpan(Color.RED), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        binding.playerTitle.append(spannableString);
    }
}
