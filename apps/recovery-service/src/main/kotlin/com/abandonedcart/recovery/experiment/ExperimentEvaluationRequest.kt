package com.abandonedcart.recovery.experiment

data class ExperimentEvaluationRequest(
    val experimentName: String,
    val cartId: String,
    val tenantId: String,
    val userId: String?,
    val anonymousId: String?,
)
