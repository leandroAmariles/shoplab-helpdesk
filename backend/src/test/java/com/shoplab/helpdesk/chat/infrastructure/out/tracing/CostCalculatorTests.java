package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shoplab.helpdesk.chat.config.TracingProperties.Price;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostCalculatorTests {

    private final CostCalculator calculator = new CostCalculator(Map.of(
            "ollama", new Price(0.0, 0.0),
            "paid-model", new Price(3.0, 15.0)));

    @Test
    void aModelWithItsOwnPriceIsChargedByTheTokensOfEachSide() {
        CostCalculator.Cost cost = calculator.cost("paid-model", "ollama", new TokenUsage(1_000, 2_000));

        assertThat(cost.status()).isEqualTo(TracingConstants.COST_PRICED);
        assertThat(cost.input()).isCloseTo(0.003, within(1e-12));
        assertThat(cost.output()).isCloseTo(0.03, within(1e-12));
        assertThat(cost.total()).isCloseTo(0.033, within(1e-12));
    }

    @Test
    void theModelNameWinsOverTheProvider() {
        CostCalculator.Cost cost = calculator.cost("paid-model", "ollama", new TokenUsage(1_000_000, 0));

        assertThat(cost.input()).isEqualTo(3.0);
    }

    @Test
    void aLocalModelIsPricedAtZeroThroughItsProvider() {
        CostCalculator.Cost cost = calculator.cost("llama3.2:3b", "ollama", new TokenUsage(500, 700));

        assertThat(cost.status()).isEqualTo(TracingConstants.COST_PRICED);
        assertThat(cost.input()).isZero();
        assertThat(cost.output()).isZero();
        assertThat(cost.total()).isZero();
    }

    @Test
    void aModelWithoutAnyPriceIsUnpricedAndHasNoAmounts() {
        CostCalculator.Cost cost = calculator.cost("mystery", "other-provider", new TokenUsage(10, 10));

        assertThat(cost.status()).isEqualTo(TracingConstants.COST_UNPRICED);
        assertThat(cost.hasAmounts()).isFalse();
    }

    @Test
    void anUnknownUsageHasNoCostEvenForAPricedModel() {
        CostCalculator.Cost cost = calculator.cost("paid-model", "ollama", TokenUsage.unknown());

        assertThat(cost.status()).isEqualTo(TracingConstants.COST_UNKNOWN_USAGE);
        assertThat(cost.hasAmounts()).isFalse();
    }
}
