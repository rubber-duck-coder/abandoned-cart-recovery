package com.abandonedcart.recovery.policy

import com.abandonedcart.recovery.experiment.ExperimentClient
import com.abandonedcart.recovery.experiment.ExperimentEvaluationRequest
import com.abandonedcart.recovery.experiment.MockExperimentClient

class RecoveryPolicyService(
    private val experimentClient: ExperimentClient,
    private val experimentName: String = MockExperimentClient.DEFAULT_EXPERIMENT_NAME,
) {
    fun resolve(request: PolicyResolutionRequest): ResolvedRecoveryPolicy {
        val assignment = experimentClient.evaluate(
            ExperimentEvaluationRequest(
                experimentName = experimentName,
                cartId = request.cartId,
                tenantId = request.tenantId,
                userId = request.userId,
                anonymousId = request.anonymousId,
            ),
        )

        return ResolvedRecoveryPolicy(
            experimentId = assignment.experimentId,
            experimentName = assignment.experimentName,
            variantId = assignment.variantId,
            policyId = "default-waterfall-${assignment.variantId}",
            policyVersion = 1,
            touches = listOf(
                RecoveryTouch(
                    touchIndex = 1,
                    delayHours = 24,
                    channel = "push",
                    templateKey = "push-${assignment.variantId}",
                ),
                RecoveryTouch(
                    touchIndex = 2,
                    delayHours = 72,
                    channel = "sms",
                    templateKey = "sms-${assignment.variantId}",
                ),
                RecoveryTouch(
                    touchIndex = 3,
                    delayHours = 168,
                    channel = "email",
                    templateKey = "email-${assignment.variantId}",
                ),
            ),
        )
    }
}
