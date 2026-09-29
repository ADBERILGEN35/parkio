package com.parkio.parking.externalsource.izelman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IzelmanCsvReaderTest {
    private final IzelmanCsvReader reader = new IzelmanCsvReader();

    @Test
    void readsBomAndDetectsSemicolon() {
        byte[] body = "OTOPARK_ADI;ILCE\nİnciraltı;Balçova\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xef; bytes[1] = (byte) 0xbb; bytes[2] = (byte) 0xbf;
        System.arraycopy(body, 0, bytes, 3, body.length);
        var parsed = reader.read(bytes);
        assertThat(parsed.delimiter()).isEqualTo(';');
        assertThat(parsed.encoding()).isEqualTo("UTF-8-BOM");
        assertThat(parsed.rows().getFirst().get("OTOPARK_ADI")).isEqualTo("İnciraltı");
    }

    @Test
    void fallsBackToWindows1254AndDetectsComma() {
        var parsed = reader.read("OTOPARK_ADI,ILCE\nÇankaya,Konak\n".getBytes(Charset.forName("windows-1254")));
        assertThat(parsed.encoding()).isEqualTo("windows-1254");
        assertThat(parsed.delimiter()).isEqualTo(',');
    }

    @Test
    void rejectsFormulaInjection() {
        assertThatThrownBy(() -> reader.read("OTOPARK_ADI;ILCE\n=cmd;Konak\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("formula-like");
    }
}
