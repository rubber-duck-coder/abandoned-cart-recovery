package com.abandonedcart.recovery.eligibility

import com.abandonedcart.recovery.repository.CartRecoveryState

class EligibilityEvaluator {
    fun evaluate(state: CartRecoveryState?): EligibilityDecision {
        if (state == null) {
            return EligibilityDecision(false, "missing_cart_state")
        }
        return when (state.cartStatus) {
            "PURCHASED" -> EligibilityDecision(false, "cart_purchased")
            "DELETED" -> EligibilityDecision(false, "cart_deleted")
            else -> EligibilityDecision(true)
        }
    }
}
