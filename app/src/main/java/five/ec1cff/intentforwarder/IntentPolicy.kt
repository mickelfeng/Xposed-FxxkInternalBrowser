package five.ec1cff.intentforwarder



data class IntentPolicy(
    val packageName: String,
    val className: String,
    val urlExtraKey: String?,
    val defaultAction: Action = Action.ASK,
    val hostAction: Map<String, Action> = HashMap()
    ) {
    enum class Action {
        PASS, REPLACE, ASK
    }
}