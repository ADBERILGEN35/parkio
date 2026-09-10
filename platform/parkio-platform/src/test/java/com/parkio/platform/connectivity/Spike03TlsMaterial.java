package com.parkio.platform.connectivity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Generates ephemeral CA + server certificates for SPIKE-03 Mode A TLS probes.
 * Material stays under a temp directory (or gitignored work dir) and must never be committed.
 */
final class Spike03TlsMaterial {

    static final String SERVER_HOSTNAME = "pg-tls.parkio.test";
    static final String MISMATCH_HOSTNAME = "wrong-host.parkio.test";

    private final Path workDir;
    private final Path caCert;
    private final Path caKey;
    private final Path serverCert;
    private final Path serverKey;

    private Spike03TlsMaterial(Path workDir) {
        this.workDir = workDir;
        this.caCert = workDir.resolve("ca.crt");
        this.caKey = workDir.resolve("ca.key");
        this.serverCert = workDir.resolve("server.crt");
        this.serverKey = workDir.resolve("server.key");
    }

    static Spike03TlsMaterial generate() throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("spike03-tls-");
        Spike03TlsMaterial material = new Spike03TlsMaterial(dir);
        material.create();
        return material;
    }

    Path workDir() {
        return workDir;
    }

    Path caCert() {
        return caCert;
    }

    Path serverCert() {
        return serverCert;
    }

    Path serverKey() {
        return serverKey;
    }

    void deleteQuietly() {
        try {
            if (!Files.exists(workDir)) {
                return;
            }
            try (var walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
            }
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private void create() throws IOException, InterruptedException {
        runOpenssl(
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                caKey.toString(),
                "-out",
                caCert.toString(),
                "-days",
                "1",
                "-subj",
                "/CN=Parkio SPIKE-03 Test CA");

        Path serverCsr = workDir.resolve("server.csr");
        Path extFile = workDir.resolve("server.ext");
        Files.writeString(
                extFile,
                "subjectAltName=DNS:localhost\n" + "extendedKeyUsage=serverAuth\n",
                StandardCharsets.UTF_8);

        runOpenssl(
                "req",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                serverKey.toString(),
                "-out",
                serverCsr.toString(),
                "-subj",
                "/CN=localhost");


        runOpenssl(
                "x509",
                "-req",
                "-in",
                serverCsr.toString(),
                "-CA",
                caCert.toString(),
                "-CAkey",
                caKey.toString(),
                "-CAcreateserial",
                "-out",
                serverCert.toString(),
                "-days",
                "1",
                "-extfile",
                extFile.toString());

        // PostgreSQL requires key mode 0600 inside Linux containers.
        serverKey.toFile().setReadable(false, false);
        serverKey.toFile().setReadable(true, true);
        serverKey.toFile().setWritable(false, false);
        serverKey.toFile().setWritable(true, true);
    }

    private static void runOpenssl(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveOpenssl());
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Avoid broken host OPENSSL_CONF (e.g. missing psqlODBC openssl.cnf on Windows).
        pb.environment().remove("OPENSSL_CONF");
        pb.environment().remove("OPENSSL_CONF_INCLUDE");
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("openssl timed out: " + String.join(" ", args));
        }
        if (process.exitValue() != 0) {
            throw new IOException("openssl failed (" + process.exitValue() + "): " + output);
        }
    }

    private static String resolveOpenssl() {
        String env = System.getenv("PARKIO_OPENSSL");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) {
            return env;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // Prefer Git OpenSSL — PATH often resolves to a broken PostgreSQL ODBC openssl.exe.
            for (String candidate : List.of(
                    "C:\\Program Files\\Git\\usr\\bin\\openssl.exe",
                    "C:\\Program Files (x86)\\Git\\usr\\bin\\openssl.exe")) {
                Path gitOpenssl = Path.of(candidate);
                if (Files.isRegularFile(gitOpenssl)) {
                    return gitOpenssl.toString();
                }
            }
        }
        return "openssl";
    }
}
