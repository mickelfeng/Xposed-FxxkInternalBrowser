package five.ec1cff.intentforwarder

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.CheckBox
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.*
import com.google.gson.Gson
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

const val ATMS = "com.android.server.wm.ActivityTaskManagerService"
const val CONFIG_PATH = "/data/system/intentfw.json"

data class State(
    val enabled: Boolean,
    val ip: MutableList<IntentPolicy>,
    val hp: MutableList<HostPolicy>
)

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class HookSystem : IXposedHookLoadPackage {
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

    private val controller = object : IController.Stub() {
        override fun getState(): Boolean {
            return enabled
        }

        override fun setState(isEnabled: Boolean) {
            enabled = isEnabled
        }

        override fun dumpService(): String {
            val dumpStr =
                "enabled=$enabled\nIntentPolicies(${intentPolicies.size})=$intentPolicies\nHostPolicies(${hostPolicies.size})=$hostPolicies"
            Log.d(TAG, dumpStr)
            return dumpStr
        }

        override fun dumpJson() {
            dumpToJson()
        }

        override fun loadJson() {
            loadFromJson()
        }
    }

    private fun dumpToJson() {
        try {
            File(CONFIG_PATH).bufferedWriter().use {
                it.write(
                    Gson().toJson(State(enabled, intentPolicies, hostPolicies))
                )
            }
            Log.d(TAG, "dump to json")
        } catch (e: Throwable) {
            Log.e(TAG, "failed to dump json", e)
        }
    }

    private fun loadFromJson() {
        try {
            File(CONFIG_PATH).bufferedReader().use {
                val state = Gson().fromJson(it.readText(), State::class.java)
                enabled = state.enabled
                intentPolicies.clear()
                intentPolicies.addAll(state.ip)
                hostPolicies.clear()
                hostPolicies.addAll(state.hp)
            }
            Log.d(TAG, "load from json")
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load from json", e)
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

    private fun checkIntent(
        intent: Intent,
        pkg: String
    ): Triple<IntentPolicy.Action, IntentPolicy?, Uri?> {
        Log.d(TAG, "check intent $intent")
        intentPolicies.firstOrNull { policy ->
            if (!policy.enabled || !(policy.className == intent.component?.className && policy.packageName == pkg))
                return@firstOrNull false
            val url = if (policy.urlExtraKey != null) {
                val p = Parcel.obtain()
                intent.extras?.writeToParcel(p, 0)
                val bytes = p.marshall()
                p.recycle()
                bytes.searchKeyAndValue(policy.urlExtraKey) {
                    it.startsWith("http")
                }?.let { Uri.parse(it) }
            } else {
                intent.data
            }
            Log.d(TAG, "found policy $policy for ($url) $intent")
            if (url == null) return@firstOrNull false
            var act = policy.defaultAction
            val host = url.host
            if (host != null) {
                matchHostPolicy(host, pkg)?.let {
                    Log.d(TAG, "$host match host policy $it")
                    act = it.action
                }
            }
            Log.d(TAG, "${policy.urlExtraKey} $act, $policy, $url")
            return Triple(act, policy, url)
        }
        return Triple(IntentPolicy.Action.PASS, null, null)
    }

    private fun modifyIntent(uri: Uri, param: XC_MethodHook.MethodHookParam) {
        val intentToSend = param.args[3] as Intent
        val newIntent = Intent(Intent.ACTION_VIEW, uri)
        newIntent.flags = intentToSend.flags
        param.args[3] = newIntent
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
            Log.e(TAG, "failed to resend:", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun askUser(
        uri: Uri,
        policy: IntentPolicy,
        param: XC_MethodHook.MethodHookParam,
        userId: Int
    ) {
        var remember = false
        val context = systemUIContext
        val rememberCheckBox = CheckBox(context)
        rememberCheckBox.setOnCheckedChangeListener { _, checked ->
            remember = checked
        }
        val intentToSend = param.args[3] as Intent
        rememberCheckBox.text = "Remember my choice for ${uri.host}"
        fun onUserChoose(shouldModify: Boolean) {
            if (remember) {
                val act = if (shouldModify) IntentPolicy.Action.REPLACE
                else IntentPolicy.Action.PASS
                val p = hostPolicies.find {
                    it.hostName == uri.host && it.pkg == intentToSend.component!!.packageName
                }
                if (p != null) {
                    p.action = act
                } else {
                    hostPolicies.add(
                        0,
                        HostPolicy(uri.host!!, intentToSend.component!!.packageName, act)
                    )
                }
                dumpToJson()
            }
            if (shouldModify) {
                modifyIntent(uri, param)
            }
            resend(param.thisObject, param.args, userId)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(rememberCheckBox)
            .setPositiveButton("Direct") { _, _ ->
                onUserChoose(false)
            }
            .setNegativeButton("Replace") { _, _ ->
                onUserChoose(true)
            }
            .setMessage("An internal browser wants to open $uri")
            .create()
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

        if (File(CONFIG_PATH).isFile) {
            loadFromJson()
        } else {
            hostPolicies.addAll(
                listOf(
                    HostPolicy("weixin.com", "five.ec1cff.wxdemo", IntentPolicy.Action.PASS),
                    HostPolicy(".qq.com", "com.tencent.mobileqq", IntentPolicy.Action.PASS),
                    HostPolicy(".qq.com", "com.tencent.mm", IntentPolicy.Action.PASS),
                    HostPolicy(".tenpay.com", "com.tencent.mobileqq", IntentPolicy.Action.PASS),
                    HostPolicy(".bilibili.com", "tv.danmaku.bili", IntentPolicy.Action.PASS),
                    HostPolicy("b23.tv", "tv.danmaku.bili", IntentPolicy.Action.PASS)
                )
            )

            intentPolicies.addAll(
                listOf(
                    IntentPolicy(
                        "five.ec1cff.wxdemo",
                        "five.ec1cff.wxdemo.WebBrowserActivity",
                        null
                    ),
                    IntentPolicy(
                        "five.ec1cff.wxdemo",
                        "five.ec1cff.wxdemo.WebBrowserActivity",
                        "extra_url"
                    ),
                    IntentPolicy(
                        "com.tencent.mobileqq",
                        "com.tencent.mobileqq.activity.QQBrowserDelegationActivity",
                        "url"
                    ),
                    // IntentPolicy("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", "rawUrl"),
                    IntentPolicy(
                        "com.tencent.mm",
                        "com.tencent.mm.plugin.webview.ui.tools.WebviewMpUI",
                        "rawUrl"
                    ),
                    IntentPolicy("tv.danmaku.bili", "tv.danmaku.bili.ui.webview.MWebActivity", null)
                )
            )
            dumpToJson()
        }

        EzXHelperInit.initHandleLoadPackage(lpparam)

        findMethod(ATMS) {
            name == "startActivity"
        }.hookBefore { param ->
            if (!enabled) return@hookBefore
            val intent = param.args[3] as? Intent ?: return@hookBefore
            val callingPackage = param.args[1] as? String ?: return@hookBefore
            val (action, policy, uri) = checkIntent(intent, callingPackage)
            if (policy == null || uri == null || action == IntentPolicy.Action.PASS) return@hookBefore
            else if (action == IntentPolicy.Action.ASK) {
                myHandler.post {
                    askUser(
                        uri,
                        policy,
                        param,
                        Binder.getCallingUid() / 100000
                    )
                }
            } else if (action == IntentPolicy.Action.REPLACE) {
                modifyIntent(uri, param)
                return@hookBefore
            }
            param.result = 0 // ActivityManager.START_SUCCESS
        }

        // bridge between system_server and module
        findMethod(ATMS) {
            name == BRIDGE_METHOD
        }.hookBefore { param ->
            if (!checkFromModule(true)) return@hookBefore
            val binder = param.args[0] as IBinder ?: return@hookBefore
            if (binder.interfaceDescriptor == GET_CONTROLLER_TOKEN) {
                param.result = controller
            }
        }
    }
}
