package com.abandonedcart.recovery.notification

import com.abandonedcart.recovery.repository.RecoveryAttempt

class MockNotificationSender : NotificationSender {
    override fun send(attempt: RecoveryAttempt): NotificationSendResult {
        val providerMessageId = "mock-${attempt.attemptId}"
        return NotificationSendResult(
            providerMessageId = providerMessageId,
            payloadJson = """{"provider":"mock","providerMessageId":"$providerMessageId"}""",
        )
    }
}
