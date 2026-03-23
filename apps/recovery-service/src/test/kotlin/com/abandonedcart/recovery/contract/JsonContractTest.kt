package com.abandonedcart.recovery.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonContractTest {
    private val jsonCodec = JsonCodec()

    @Test
    fun `cart mutation event round trips through json`() {
        val event = CartMutationEvent(
            cartId = "cart-1",
            tenantId = "tenant-1",
            mutationType = "item_added",
            occurredAt = "2026-03-23T13:59:00Z",
            attributes = mapOf("sku" to "sku-1"),
        )

        val json = jsonCodec.toJson(event)
        val decoded = jsonCodec.fromJson<CartMutationEvent>(json)

        assertEquals("cart-1", decoded.cartId)
        assertEquals("item_added", decoded.mutationType)
        assertEquals("sku-1", decoded.attributes["sku"])
    }

    @Test
    fun `analytics event json contains event type`() {
        val event = AnalyticsEvent(
            eventType = "attempt_scheduled",
            cartId = "cart-2",
            occurredAt = "2026-03-23T14:00:00Z",
            attributes = mapOf("channel" to "push"),
        )

        val json = jsonCodec.toJson(event)

        assertTrue(json.contains("attempt_scheduled"))
        assertTrue(json.contains("cart-2"))
    }
}

