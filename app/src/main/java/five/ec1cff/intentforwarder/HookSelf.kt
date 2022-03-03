package five.ec1cff.intentforwarder

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookSelf : IXposedHookLoadPackage {
    val TAG = "IntentForwardHookSelf"

    @SuppressLint("PrivateApi")
    fun requestController(): IBinder? {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .invokeStaticMethodAuto("getService", "activity_task")
            val service = Class.forName("android.app.IActivityTaskManager\$Stub")
                .invokeStaticMethodAuto("asInterface", binder)
            return service?.invokeMethodAuto(
                BRIDGE_METHOD,
                Binder(GET_CONTROLLER_TOKEN)
            ) as? IBinder
        } catch (e: Throwable) {
            Log.e(TAG, "failed to init", e)
        }
        return null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != BuildConfig.APPLICATION_ID) return
        Log.d(TAG, "hook self")

        XposedHelpers.findClass(MyApplication::class.java.name, lpparam.classLoader)
            .getDeclaredField("binder").also {
            Log.d(TAG, it.toString())
            it.isAccessible = true
        }.set(null, requestController())
    }
}
