package com.abandonedcart.recovery.notification

import com.abandonedcart.recovery.repository.RecoveryAttempt

interface NotificationSender {
    fun send(attempt: RecoveryAttempt): NotificationSendResult
}
