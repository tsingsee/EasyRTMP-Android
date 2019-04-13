package org.easydarwin.easypusher;

import android.databinding.DataBindingUtil;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
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
        binding.version.append("(");

        SpannableString
                spannableString;
        if (EasyApplication.activeDays >= 9999) {
            spannableString = new SpannableString("激活码永久有效");
            spannableString.setSpan(new ForegroundColorSpan(Color.GREEN), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }else if (EasyApplication.activeDays > 0){
            spannableString = new SpannableString(String.format("激活码还剩%d天可用",EasyApplication.activeDays));
            spannableString.setSpan(new ForegroundColorSpan(Color.YELLOW), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }else{
            spannableString = new SpannableString(String.format("激活码已过期(%d)",EasyApplication.activeDays));
            spannableString.setSpan(new ForegroundColorSpan(Color.RED), 0, spannableString.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }

        binding.version.append(spannableString);
        binding.version.append(")");


        binding.serverTitle.setText("-EasyDSS RTMP流媒体服务器：\n");
        binding.serverTitle.setMovementMethod(LinkMovementMethod.getInstance());

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
