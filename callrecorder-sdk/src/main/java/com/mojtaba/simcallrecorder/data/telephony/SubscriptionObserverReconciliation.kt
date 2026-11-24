package com.mojtaba.simcallrecorder.data.telephony

/**
 * Pure reconciliation plan for subscription-scoped observers.
 *
 * Subscription IDs remain in-memory platform keys only. A slot move for an existing subscription
 * requires restarting its observer because callbacks capture the slot index at registration time.
 */
internal data class SubscriptionObserverPlan<T>(
    val toStart: List<T>,
    val toStop: Set<Int>,
)

internal fun <T> planSubscriptionObserverChanges(
    currentlyObserved: Map<Int, Int>,
    currentSubscriptions: List<T>,
    subscriptionId: (T) -> Int,
    slotIndex: (T) -> Int,
): SubscriptionObserverPlan<T> {
    val desired = currentSubscriptions.associateBy(subscriptionId)
    val toStop = currentlyObserved.filter { (id, observedSlot) ->
        desired[id]?.let(slotIndex) != observedSlot
    }.keys
    val toStart = desired.filter { (id, subscription) ->
        currentlyObserved[id] != slotIndex(subscription)
    }.values.toList()
    return SubscriptionObserverPlan(toStart = toStart, toStop = toStop)
}
