package org.rv4a.captureprobe;
import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.os.*;
import android.widget.TextView;

/** Only this fixture requests projection; tests must accept the real system dialog. */
public class ProbeActivity extends Activity {
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label=new TextView(this); label.setText("Synthetic audio capture test. No Spotify or ViPER session is modified."); setContentView(label);
        if(state==null) {
            MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
            Intent consent=Build.VERSION.SDK_INT>=34 ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) : manager.createScreenCaptureIntent();
            startActivityForResult(consent, 7);
        }
    }
    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==7 && result==RESULT_OK && data!=null)
            startForegroundService(new Intent(this,ProbeService.class).putExtra("grant",data));
        else finish();
    }
}
