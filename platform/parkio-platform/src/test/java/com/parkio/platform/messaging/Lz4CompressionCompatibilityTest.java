package com.parkio.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.xxhash.XXHashFactory;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.SimpleRecord;
import org.junit.jupiter.api.Test;

/**
 * The platform pins lz4-java to 1.11.4 (CVE-2026-106451 and five lower CVEs in the 1.10.1 that kafka-clients 3.9.2
 * pulls in). These tests prove that the resolved artifact is the fixed one and that Kafka's LZ4 codec and the
 * native LZ4/xxHash implementations still work with it.
 */
class Lz4CompressionCompatibilityTest {

    @Test
    void theResolvedLz4JavaIsTheFixedRelease() throws Exception {
        String location = LZ4Factory.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertThat(location).contains("lz4-java-1.11.4");
        try (JarFile jar = new JarFile(location)) {
            String version = jar.getManifest().getMainAttributes().getValue("Bundle-Version");
            if (version != null) {
                assertThat(version).startsWith("1.11.4");
            }
        }
    }

    @Test
    void kafkaLz4RecordBatchesRoundTrip() {
        List<SimpleRecord> sent = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String value = ("{\"eventId\":\"e-" + i + "\",\"payload\":\"" + "parkio-".repeat(i % 17 + 1) + "\"}");
            sent.add(new SimpleRecord(i, ("k-" + i).getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8)));
        }
        MemoryRecords records = MemoryRecords.withRecords(Compression.lz4().build(), sent.toArray(new SimpleRecord[0]));

        List<String> received = new ArrayList<>();
        for (RecordBatch batch : records.batches()) {
            assertThat(batch.compressionType().name).isEqualTo("lz4");
            for (Record record : batch) {
                received.add(StandardCharsets.UTF_8.decode(record.value()).toString());
            }
        }
        assertThat(received).hasSize(sent.size());
        for (int i = 0; i < sent.size(); i++) {
            assertThat(received.get(i)).isEqualTo(StandardCharsets.UTF_8.decode(sent.get(i).value().duplicate()).toString());
        }
    }

    @Test
    void nativeLz4AndXxHashWorkOnLinuxAmd64() {
        // The service images run on linux/amd64, where lz4-java extracts and loads its JNI library.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64")), "native check runs on linux/amd64");

        LZ4Factory nativeFactory = LZ4Factory.nativeInstance();
        LZ4Compressor compressor = nativeFactory.fastCompressor();
        LZ4FastDecompressor decompressor = nativeFactory.fastDecompressor();
        byte[] input = "parkio lz4 native round trip ".repeat(64).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compressor.compress(input);
        byte[] restored = decompressor.decompress(compressed, input.length);
        assertThat(restored).isEqualTo(input);

        int nativeHash = XXHashFactory.nativeInstance().hash32().hash(ByteBuffer.wrap(input), 0, input.length, 0x9747b28c);
        int javaHash = XXHashFactory.safeInstance().hash32().hash(ByteBuffer.wrap(input), 0, input.length, 0x9747b28c);
        assertThat(nativeHash).isEqualTo(javaHash);
        assertThat(LZ4Factory.fastestInstance().toString()).isEqualTo(nativeFactory.toString());
    }
}
