package com.abandonedcart.recovery.analytics

import com.abandonedcart.recovery.contract.AnalyticsEvent

interface AnalyticsPublisher {
    fun publish(event: AnalyticsEvent)
}
