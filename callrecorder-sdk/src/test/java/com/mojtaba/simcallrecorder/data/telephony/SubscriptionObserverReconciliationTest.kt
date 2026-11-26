package com.mojtaba.simcallrecorder.data.telephony

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionObserverReconciliationTest {
    private data class Subscription(val id: Int, val slot: Int)

    @Test
    fun startsNewSubscriptionsAndStopsRemovedSubscriptions() {
        val plan = planSubscriptionObserverChanges(
            currentlyObserved = mapOf(10 to 0, 20 to 1),
            currentSubscriptions = listOf(Subscription(20, 1), Subscription(30, 2)),
            subscriptionId = Subscription::id,
            slotIndex = Subscription::slot,
        )

        assertEquals(setOf(10), plan.toStop)
        assertEquals(listOf(Subscription(30, 2)), plan.toStart)
    }

    @Test
    fun doesNotRestartUnchangedSubscriptions() {
        val plan = planSubscriptionObserverChanges(
            currentlyObserved = mapOf(10 to 0, 20 to 1),
            currentSubscriptions = listOf(Subscription(10, 0), Subscription(20, 1)),
            subscriptionId = Subscription::id,
            slotIndex = Subscription::slot,
        )

        assertEquals(emptySet<Int>(), plan.toStop)
        assertEquals(emptyList<Subscription>(), plan.toStart)
    }

    @Test
    fun restartsObserverWhenSameSubscriptionMovesToAnotherSlot() {
        val plan = planSubscriptionObserverChanges(
            currentlyObserved = mapOf(10 to 0),
            currentSubscriptions = listOf(Subscription(10, 1)),
            subscriptionId = Subscription::id,
            slotIndex = Subscription::slot,
        )

        assertEquals(setOf(10), plan.toStop)
        assertEquals(listOf(Subscription(10, 1)), plan.toStart)
    }

    @Test
    fun emptySubscriptionListStopsEveryObserverButAllowsLaterReattachment() {
        val stopped = planSubscriptionObserverChanges(
            currentlyObserved = mapOf(10 to 0, 20 to 1),
            currentSubscriptions = emptyList(),
            subscriptionId = Subscription::id,
            slotIndex = Subscription::slot,
        )
        assertEquals(setOf(10, 20), stopped.toStop)
        assertEquals(emptyList<Subscription>(), stopped.toStart)

        val reattached = planSubscriptionObserverChanges(
            currentlyObserved = emptyMap(),
            currentSubscriptions = listOf(Subscription(20, 1)),
            subscriptionId = Subscription::id,
            slotIndex = Subscription::slot,
        )
        assertEquals(listOf(Subscription(20, 1)), reattached.toStart)
        assertEquals(emptySet<Int>(), reattached.toStop)
    }
}
