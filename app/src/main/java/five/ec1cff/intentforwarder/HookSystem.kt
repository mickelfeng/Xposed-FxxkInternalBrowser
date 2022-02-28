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

    private val policies: MutableList<IntentPolicy> = ArrayList()

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

    private fun checkIntent(intent: Intent, pkg: String): Pair<IntentPolicy?, IntentPolicy.Action> {
        policies.firstOrNull {
            it.className == intent.component?.className && it.packageName == pkg
        }?.let {
            val url = if (it.urlExtraKey != null) {
                (intent.extras?.get(it.urlExtraKey) as? String) ?.let {
                    Uri.parse(it)
                }
            } else {
                intent.data
            }
            var act = it.defaultAction
            val host = url?.host
            if (host != null) {
                it.hostAction.firstNotNullOfOrNull {
                    if (it.key.endsWith(host)) it.value
                    else null
                }?.let {
                    act = it
                }
            }
            return it to act
        }
        return null to IntentPolicy.Action.PASS
    }

    private fun modifyIntent(it: Intent, policy: IntentPolicy) {
        it.component = null
        it.action = Intent.ACTION_VIEW
        it.extras?.getString(policy.urlExtraKey)?.let { url ->
            it.data = Uri.parse(url)
        }
    }

    private fun resend(self: Any, args: Array<Any>, userId: Int) {
        try {
            self.invokeMethodAuto(
                "startActivityAsUser",
                *args, userId
            )?.let {
                Log.d(TAG, "direct startActivityAsUser result: $it")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to direct start:", e)
        }
    }

    private fun askAndResendStartActivity(policy: IntentPolicy, self: Any, args: Array<Any>, userId: Int) {
        val dialog = AlertDialog.Builder(systemUIContext)
            .setPositiveButton("Direct") { _, _ ->
                resend(self, args, userId)
            }
            .setNegativeButton("Replace") { _, _ ->
                (args[3] as? Intent)?.let {
                    modifyIntent(it, policy)
                }
                resend(self, args, userId)
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

        val hostAction: HashMap<String, IntentPolicy.Action> = hashMapOf(
            "weixin.com" to IntentPolicy.Action.PASS
        )

        policies.addAll(listOf(
            IntentPolicy("five.ec1cff.wxdemo", "five.ec1cff.wxdemo.WebBrowserActivity", null, hostAction = hostAction),
            IntentPolicy("five.ec1cff.wxdemo", "five.ec1cff.wxdemo.WebBrowserActivity", "extra_url", hostAction = hostAction)
        ))

        EzXHelperInit.initHandleLoadPackage(lpparam)

        findMethod(ATMS) {
            name == "startActivity"
        }.hookBefore { param ->
            if (!enabled) return@hookBefore
            val intent = param.args[3] as? Intent?: return@hookBefore
            val callingPackage = param.args[1] as? String?: return@hookBefore
            val (policy, action) = checkIntent(intent, callingPackage)
            if (policy == null || action == IntentPolicy.Action.PASS) return@hookBefore
            else if (action == IntentPolicy.Action.ASK) {
                myHandler.post {
                    askAndResendStartActivity(
                        policy,
                        param.thisObject,
                        param.args,
                        Binder.getCallingUid() / 100000
                    )
                }
            }
            else if (action == IntentPolicy.Action.REPLACE) {
                modifyIntent(intent, policy)
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