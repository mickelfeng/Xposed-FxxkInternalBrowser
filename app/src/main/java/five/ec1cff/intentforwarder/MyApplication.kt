package five.ec1cff.intentforwarder

import android.app.Application
import android.os.IBinder

const val ACTION_FORWARD_INTENT = "five.ec1cff.action.FORWARD_INTENT"
const val ACTION_REQUEST_FORWARD = "five.ec1cff.action.REQUEST_FORWARD_INTENT"
const val GET_CONTROLLER_TOKEN = "five.ec1cff.intentforwarder.GET_CONTROLLER"
const val BRIDGE_METHOD = "requestStartActivityPermissionToken"

class MyApplication: Application() {
    companion object {
        var binder: IBinder? = null
        val controller: IController? by lazy {
            IController.Stub.asInterface(binder)
        }
    }
}