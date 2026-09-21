package com.parkio.gateway.application.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaitlistCsvTest {

    @Test
    void quotesAndEscapesQuotes() {
        assertThat(WaitlistCsv.cell("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(WaitlistCsv.cell(null)).isEqualTo("\"\"");
        assertThat(WaitlistCsv.cell("")).isEqualTo("\"\"");
    }

    @Test
    void prefixesFormulaInjectionPrefixes() {
        assertThat(WaitlistCsv.cell("=CMD()")).isEqualTo("\"'=CMD()\"");
        assertThat(WaitlistCsv.cell("+1+1")).isEqualTo("\"'+1+1\"");
        assertThat(WaitlistCsv.cell("-1+1")).isEqualTo("\"'-1+1\"");
        assertThat(WaitlistCsv.cell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
    }
}
