package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import com.shoplab.helpdesk.chat.config.TracingProperties.Price;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import java.util.Map;

/**
 * The cost of a generation from its tokens and the price table. Langfuse does not know local models, so the backend
 * works the cost out: a local model costs 0 (priced through its provider), a model without a price has no cost, and an
 * unknown usage has none either (specs/LAB-64, FR-014).
 */
public class CostCalculator {

    private static final double TOKENS_PER_PRICE_UNIT = 1_000_000.0;

    private final Map<String, Price> pricing;

    public CostCalculator(Map<String, Price> pricing) {
        this.pricing = Map.copyOf(pricing);
    }

    /**
     * The cost of one generation, in USD.
     *
     * @param status {@code priced}, {@code unpriced} or {@code unknown_usage}
     * @param input  cost of the prompt, {@code null} unless priced
     * @param output cost of the answer, {@code null} unless priced
     */
    public record Cost(String status, Double input, Double output) {

        public boolean hasAmounts() {
            return input != null && output != null;
        }

        public double total() {
            return hasAmounts() ? input + output : 0.0;
        }
    }

    /** Looks the price up by model name first and by provider after. */
    public Cost cost(String model, String provider, TokenUsage usage) {
        if (!usage.isKnown()) {
            return new Cost(TracingConstants.COST_UNKNOWN_USAGE, null, null);
        }
        Price price = pricing.get(model);
        if (price == null) {
            price = pricing.get(provider);
        }
        if (price == null) {
            return new Cost(TracingConstants.COST_UNPRICED, null, null);
        }
        return new Cost(TracingConstants.COST_PRICED,
                usage.inputTokens() * price.inputPerMillion() / TOKENS_PER_PRICE_UNIT,
                usage.outputTokens() * price.outputPerMillion() / TOKENS_PER_PRICE_UNIT);
    }
}
