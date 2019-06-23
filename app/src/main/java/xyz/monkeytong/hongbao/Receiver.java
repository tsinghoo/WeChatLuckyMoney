package xyz.monkeytong.hongbao;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import xyz.monkeytong.hongbao.services.HongbaoService;

public class Receiver extends BroadcastReceiver {
    private static String TAG="Receiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        String type = intent.getStringExtra("type");
        if (type == null) {
            return;
        }

        Log.i(TAG, "onNewIntent:" + type);

        if (type.equals("updatePreference")) {
            HongbaoService.updatePreference(intent.getStringExtra("key"), intent.getStringExtra("value"));
        }

    }
}
