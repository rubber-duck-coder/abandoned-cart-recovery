package com.abandonedcart.recovery.notification

data class NotificationSendResult(
    val providerMessageId: String,
    val payloadJson: String,
)
