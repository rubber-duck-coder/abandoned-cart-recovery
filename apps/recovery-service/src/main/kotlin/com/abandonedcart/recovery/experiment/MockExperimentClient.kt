package com.abandonedcart.recovery.experiment

class MockExperimentClient(
    private val supportedExperiments: Map<String, String> = mapOf(
        DEFAULT_EXPERIMENT_NAME to DEFAULT_EXPERIMENT_ID,
    ),
) : ExperimentClient {
    override fun evaluate(request: ExperimentEvaluationRequest): ExperimentAssignment {
        val experimentId = supportedExperiments[request.experimentName]
            ?: throw IllegalArgumentException("Unknown experiment: ${request.experimentName}")

        val stableSubject = request.userId ?: request.anonymousId ?: request.cartId
        val variantId = if (stableSubject.hashCode() % 2 == 0) "control" else "treatment"

        return ExperimentAssignment(
            experimentId = experimentId,
            experimentName = request.experimentName,
            variantId = variantId,
        )
    }

    companion object {
        const val DEFAULT_EXPERIMENT_NAME = "abandoned-cart-recovery-policy"
        const val DEFAULT_EXPERIMENT_ID = "exp-abandoned-cart-recovery-policy"
    }
}
