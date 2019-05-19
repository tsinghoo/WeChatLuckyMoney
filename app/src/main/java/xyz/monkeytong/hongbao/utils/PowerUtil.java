package xyz.monkeytong.hongbao.utils;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/**
 * Created by Zhongyi on 1/29/16.
 */
public class PowerUtil {
    private PowerManager.WakeLock wakeLock;
    private KeyguardManager.KeyguardLock keyguardLock;
    public static String TAG = PowerUtil.class.getSimpleName();

    public PowerUtil(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "Lbdd:HongbaoWakelock");
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        keyguardLock = km.newKeyguardLock("HongbaoKeyguardLock");
    }

    private void acquire() {
        Log.i(TAG, "disable keyguard");
        wakeLock.acquire(200000);
        keyguardLock.disableKeyguard();
    }

    private void release() {
        Log.i(TAG, "release keyguard");
        if (wakeLock.isHeld()) {
            wakeLock.release();
            keyguardLock.reenableKeyguard();
        }
    }

    public void handleWakeLock(boolean isWake) {
        if (isWake) {
            this.acquire();
        } else {
            this.release();
        }
    }
}
