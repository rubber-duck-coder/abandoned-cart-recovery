package com.abandonedcart.recovery.policy

data class RecoveryTouch(
    val touchIndex: Int,
    val delayHours: Long,
    val channel: String,
    val templateKey: String,
)
