package com.abandonedcart.recovery.frequencycap

import com.abandonedcart.recovery.repository.RecoveryAttempt

class MockFrequencyCapClient : FrequencyCapClient {
    override fun evaluate(attempt: RecoveryAttempt): FrequencyCapDecision = FrequencyCapDecision(true, "allowed")
}
