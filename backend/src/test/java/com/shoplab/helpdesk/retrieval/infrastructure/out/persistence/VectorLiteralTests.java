package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class VectorLiteralTests {

    @Test
    void writesTheValuesBetweenBracketsSeparatedByCommas() {
        assertThat(VectorLiteral.of(new float[] {1.0f, -0.5f, 0.25f})).isEqualTo("[1.0,-0.5,0.25]");
    }

    @Test
    void usesADotAsDecimalSeparatorWhateverTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("es-CO"));
        try {
            assertThat(VectorLiteral.of(new float[] {0.5f})).isEqualTo("[0.5]");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void writesAFullSizeVector() {
        String literal = VectorLiteral.of(new float[1024]);

        assertThat(literal).startsWith("[0.0,").endsWith(",0.0]");
        assertThat(literal.split(",")).hasSize(1024);
    }

    @Test
    void anEmptyOrMissingVectorIsRejected() {
        assertThatThrownBy(() -> VectorLiteral.of(new float[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorLiteral.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
