package five.ec1cff.intentforwarder

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class IntentPolicy(
    val packageName: String,
    val className: String,
    val urlExtraKey: String?,
    val defaultAction: Action = Action.ASK,
    var enabled: Boolean = true
) : Parcelable {
    enum class Action {
        PASS, REPLACE, ASK
    }
}

@Parcelize
data class HostPolicy(
    val hostName: String,
    val pkg: String,
    var action: IntentPolicy.Action
) : Parcelable

val intentPolicies: MutableList<IntentPolicy> by lazy {
    ArrayList()
}

val hostPolicies: MutableList<HostPolicy> by lazy {
    ArrayList()
}

fun matchHostPolicy(host: String, pkg: String): HostPolicy? {
    var bestMatch: HostPolicy? = null
    hostPolicies.forEach { policy ->
        if (policy.pkg != pkg) return@forEach
        if (!policy.hostName.startsWith(".")) {
            if (host == policy.hostName)
                return policy
        } else {
            if (host.endsWith(policy.hostName.substring(1))) {
                if (bestMatch == null || bestMatch!!.hostName.length < policy.hostName.length) {
                    bestMatch = policy
                }
            }
        }
    }
    return bestMatch
}
