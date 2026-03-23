package com.abandonedcart.recovery.frequencycap

import com.abandonedcart.recovery.repository.RecoveryAttempt

interface FrequencyCapClient {
    fun evaluate(attempt: RecoveryAttempt): FrequencyCapDecision
}
