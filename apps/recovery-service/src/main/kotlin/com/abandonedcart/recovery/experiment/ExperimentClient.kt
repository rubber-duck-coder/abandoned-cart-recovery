package com.abandonedcart.recovery.experiment

interface ExperimentClient {
    fun evaluate(request: ExperimentEvaluationRequest): ExperimentAssignment
}
