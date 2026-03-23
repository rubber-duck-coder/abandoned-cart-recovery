package com.abandonedcart.recovery.policy

import com.abandonedcart.recovery.experiment.MockExperimentClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecoveryPolicyServiceTest {
    @Test
    fun `same subject resolves the same assignment repeatedly`() {
        val service = RecoveryPolicyService(MockExperimentClient())
        val request = PolicyResolutionRequest(
            cartId = "cart-1",
            tenantId = "tenant-1",
            userId = "user-1",
            anonymousId = "anon-1",
        )

        val first = service.resolve(request)
        val second = service.resolve(request)

        assertEquals(first.experimentId, second.experimentId)
        assertEquals(first.experimentName, second.experimentName)
        assertEquals(first.variantId, second.variantId)
        assertEquals(first.policyId, second.policyId)
    }

    @Test
    fun `resolved policy returns the default waterfall touches`() {
        val service = RecoveryPolicyService(MockExperimentClient())

        val resolved = service.resolve(
            PolicyResolutionRequest(
                cartId = "cart-2",
                tenantId = "tenant-1",
                userId = "user-2",
                anonymousId = null,
            ),
        )

        assertEquals(3, resolved.touches.size)
        assertEquals(listOf(24L, 72L, 168L), resolved.touches.map { it.delayHours })
        assertEquals(listOf("push", "sms", "email"), resolved.touches.map { it.channel })
        assertEquals("abandoned-cart-recovery-policy", resolved.experimentName)
        assertEquals(1, resolved.policyVersion)
    }

    @Test
    fun `unknown experiment configuration fails clearly`() {
        val service = RecoveryPolicyService(
            experimentClient = MockExperimentClient(),
            experimentName = "missing-experiment",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.resolve(
                PolicyResolutionRequest(
                    cartId = "cart-3",
                    tenantId = "tenant-1",
                    userId = null,
                    anonymousId = "anon-3",
                ),
            )
        }

        assertEquals("Unknown experiment: missing-experiment", error.message)
    }
}
