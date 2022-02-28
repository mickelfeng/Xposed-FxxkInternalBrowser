package five.ec1cff.intentforwarder

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

const val ATMS = "com.android.server.wm.ActivityTaskManagerService"

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class HookSystem: IXposedHookLoadPackage {
    val TAG = "IntentForwardHookSystem"

    private val systemContext: Context by lazy {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .invoke(null)!!
            .invokeMethod("getSystemContext") as Context
    }

    private val systemUIContext: Context by lazy {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .invoke(null)!!
            .invokeMethod("getSystemUiContext") as Context
    }

    private val windowToken: IBinder by lazy {
        Binder()
    }

    private val myHandler: Handler by lazy {
        val thread = HandlerThread("IntentForwarder")
        thread.start()
        Handler(thread.looper)
    }

    private var enabled = true

    private val controller = object: IController.Stub() {
        override fun getState(): Boolean {
            return enabled
        }

        override fun setState(isEnabled: Boolean) {
            enabled = isEnabled
        }
    }

    private fun checkFromModule(withRoot: Boolean = false): Boolean {
        val uid = Binder.getCallingUid()
        if (withRoot && uid == 0) return true
        val result = systemContext.packageManager.getPackagesForUid(uid)?.any {
            it == BuildConfig.APPLICATION_ID
        }
        Log.d(TAG, "check $uid : $result")
        return result == true
    }

    private fun checkIntent(intent: Intent): Boolean {
        return intent.component?.className == "five.ec1cff.wxdemo.WebBrowserActivity"
    }

    private fun askAndResendStartActivity(atm: Any, args: Array<Any>, userId: Int) {
        val dialog = AlertDialog.Builder(systemUIContext)
            .setPositiveButton("Direct") { _, _ ->
                try {
                    atm.invokeMethodAuto(
                        "startActivityAsUser",
                        *args, userId
                    )?.let {
                        Log.d(TAG, "direct startActivityAsUser result: $it")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to direct start:", e)
                }
            }
            .setNegativeButton("Replace") { _, _ ->
                (args[3] as? Intent)?.let {
                    it.component = null
                    it.action = Intent.ACTION_VIEW
                    it.extras?.getString("extra_url")?.let { url ->
                        it.data = Uri.parse(url)
                    }
                }
                try {
                    atm.invokeMethodAuto("startActivityAsUser",
                        *args, userId
                    )?.let {
                        Log.d(TAG, "replace startActivityAsUser result: $it")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to replace start:", e)
                }
            }
            .setMessage("IntentForwarder").create()
        dialog.window?.let {
            it.attributes?.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG
            it.attributes.token = windowToken
        }
        dialog.show()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!(lpparam.packageName == "android" && lpparam.processName == "android")) {
            return
        }

        EzXHelperInit.initHandleLoadPackage(lpparam)

        findMethod(ATMS) {
            name == "startActivity"
        }.hookBefore { param ->
            if (!enabled) return@hookBefore
            val intent = param.args[3] as? Intent?: return@hookBefore
            if (!checkIntent(intent)) return@hookBefore
            myHandler.post {
                askAndResendStartActivity(
                    param.thisObject,
                    param.args,
                    Binder.getCallingUid() / 100000
                )
            }
            param.result = 0 // ActivityManager.START_SUCCESS
        }

        // bridge between system_server and module
        findMethod(ATMS) {
            name == BRIDGE_METHOD
        }.hookBefore { param ->
            if (!checkFromModule(true)) return@hookBefore
            val binder = param.args[0] as IBinder?: return@hookBefore
            if (binder.interfaceDescriptor == GET_CONTROLLER_TOKEN) {
                param.result = controller
            }
        }
    }
}