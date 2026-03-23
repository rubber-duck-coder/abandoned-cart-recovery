package com.abandonedcart.recovery.frequencycap

import com.abandonedcart.recovery.repository.RecoveryAttempt

class MockFrequencyCapClient : FrequencyCapClient {
    override fun evaluate(attempt: RecoveryAttempt): FrequencyCapDecision {
        return if (attempt.templateKey.contains("cap-denied")) {
            FrequencyCapDecision(false, "frequency_cap_denied")
        } else {
            FrequencyCapDecision(true, "allowed")
        }
    }
}
