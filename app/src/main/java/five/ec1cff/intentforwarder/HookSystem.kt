package five.ec1cff.intentforwarder

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class HookSystem: IXposedHookLoadPackage {
    val TAG = "IntentForwardHookSystem"

    val systemContext: Context by lazy {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .invoke(null)!!
            .invokeMethod("getSystemContext") as Context
    }

    var enabled = true

    val controller = object: IController.Stub() {
        override fun getState(): Boolean {
            return enabled
        }

        override fun setState(isEnabled: Boolean) {
            enabled = isEnabled
        }
    }

    private fun checkFromModule(allowRoot: Boolean = false): Boolean {
        val uid = Binder.getCallingUid()
        if (allowRoot && uid == 0) return true
        val result = systemContext.packageManager.getPackagesForUid(uid)?.any {
            it == BuildConfig.APPLICATION_ID
        }
        Log.d(TAG, "check $uid : $result")
        return result == true
    }

    fun getRequestField(n: String) = findField("com.android.server.wm.ActivityStarter\$Request") {
        name == n
    }.also { it.isAccessible = true }

    val intentField: Field by lazy {
        getRequestField("intent")
    }

    val reasonField: Field by lazy {
        getRequestField("reason")
    }

    val resultToField: Field by lazy {
        getRequestField("resultTo")
    }

    val callingUidField: Field by lazy {
        getRequestField("callingUid")
    }

    val callingPackageField: Field by lazy {
        getRequestField("callingPackage")
    }

    val callingFeatureIdField: Field by lazy {
        getRequestField("callingFeatureId")
    }

    val ignoreTargetSecurityField: Field by lazy {
        getRequestField("ignoreTargetSecurity")
    }

    private fun handleForwardIntent(that: Any, mRequest: Any, intent: Intent) {
        with (that) {
            val extraIntent = intent.extras?.get(Intent.EXTRA_INTENT) as Intent?: return
            val resultTo = resultToField.get(mRequest)
            val sourceRecord =
                this.getObject("mService")
                    .getObject("mRootWindowContainer")
                    .invokeMethod(
                        "isInAnyStack",
                        arrayOf(resultTo),
                        arrayOf(Class.forName("android.os.IBinder"))
                    )
            Log.d(TAG, "resultTo=$resultTo, sourceRecord=$sourceRecord")
            if (sourceRecord != null && sourceRecord.getObject("app") != null) {
                callingUidField.set(
                    mRequest,
                    XposedHelpers.getObjectField(sourceRecord, "launchedFromUid")
                )
                callingPackageField.set(
                    mRequest,
                    XposedHelpers.getObjectField(sourceRecord, "launchedFromPackage")
                )
                callingFeatureIdField.set(
                    mRequest,
                    XposedHelpers.getObjectField(sourceRecord, "launchedFromFeatureId")
                )
                extraIntent.flags =
                    extraIntent.flags or Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP
                intentField.set(mRequest, extraIntent)
                reasonField.set(mRequest, ACTION_FORWARD_INTENT)
                ignoreTargetSecurityField.set(mRequest, true)
                Log.d(TAG, "replace done: $extraIntent")
            }

        }
    }

    // TODO: consider to hook ATMS#startActivity...
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!(lpparam.packageName == "android" && lpparam.processName == "android")) {
            return
        }

        EzXHelperInit.initHandleLoadPackage(lpparam)

        findMethod("com.android.server.wm.ActivityStarter") {
            name == "execute"
        }.hookBefore { param ->
            if (!enabled) return@hookBefore
            with(param.thisObject) {
                val mRequest = this.getObject("mRequest")
                val intent = intentField.get(mRequest) as Intent?
                if (intent != null) {
                    try {
                        if (intent.action == ACTION_FORWARD_INTENT && checkFromModule()) {
                            Log.w(TAG, "get intent forward request")
                            handleForwardIntent(this, mRequest, intent)
                            return@hookBefore
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "failed to handle forward intent", e)
                    }

                    if (intent.component?.className == "five.ec1cff.wxdemo.WebBrowserActivity") {
                        Log.d(TAG, "replace intent $intent")
                        val newIntent = Intent(ACTION_REQUEST_FORWARD)
                        newIntent.component = ComponentName("five.ec1cff.intentforwarder", "five.ec1cff.intentforwarder.IntentForwardActivity")
                        newIntent.putExtra(Intent.EXTRA_INTENT, intent)
                        intentField.set(mRequest, newIntent)
                        Log.d(TAG, "to intent $newIntent")
                    }
                }
            }
        }

        findMethod("com.android.server.wm.ActivityTaskManagerService") {
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