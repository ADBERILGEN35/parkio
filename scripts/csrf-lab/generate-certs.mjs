/**
 * Generate disposable self-signed TLS material for the CSRF gateway HTTPS lab.
 * Windows: New-SelfSignedCertificate. Linux/macOS CI: openssl.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const HOSTS = [
  'parkio.test',
  'app.parkio.test',
  'api.parkio.test',
  'evil.parkio.test',
  'cross.example.test',
];

export function generateLabCerts(outDir) {
  mkdirSync(outDir, { recursive: true });
  const keyPath = join(outDir, 'lab-key.pem');
  const certPath = join(outDir, 'lab-cert.pem');
  if (existsSync(keyPath) && existsSync(certPath)) {
    return { keyPath, certPath, key: readFileSync(keyPath), cert: readFileSync(certPath) };
  }

  if (process.platform === 'win32') {
    generateWindows(outDir, keyPath, certPath);
  } else {
    generateOpenssl(outDir, keyPath, certPath);
  }
  return { keyPath, certPath, key: readFileSync(keyPath), cert: readFileSync(certPath) };
}

function generateOpenssl(outDir, keyPath, certPath) {
  const conf = join(outDir, 'san.cnf');
  writeFileSync(
    conf,
    `[req]
distinguished_name=req_distinguished_name
x509_extensions=v3_req
prompt=no
[req_distinguished_name]
CN=parkio.test
[v3_req]
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=@alt_names
[alt_names]
${HOSTS.map((h, i) => `DNS.${i + 1}=${h}`).join('\n')}
`,
  );
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-sha256',
      '-days',
      '2',
      '-nodes',
      '-keyout',
      keyPath,
      '-out',
      certPath,
      '-config',
      conf,
    ],
    { stdio: 'inherit' },
  );
}

function generateWindows(outDir, keyPath, certPath) {
  const dnsArgs = HOSTS.map((h) => `"${h}"`).join(',');
  const pfx = join(outDir, 'lab.pfx');
  const ps = `
$ErrorActionPreference = 'Stop'
$cert = New-SelfSignedCertificate -DnsName @(${dnsArgs}) -CertStoreLocation Cert:\\CurrentUser\\My -KeyExportPolicy Exportable -NotAfter (Get-Date).AddDays(2) -FriendlyName 'parkio-csrf-lab'
$pwd = ConvertTo-SecureString -String 'parkio-csrf-lab' -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath '${pfx.replace(/'/g, "''")}' -Password $pwd | Out-Null
Get-ChildItem Cert:\\CurrentUser\\My\\$($cert.Thumbprint) | Remove-Item
`;
  const script = join(outDir, 'mkcert.ps1');
  writeFileSync(script, ps);
  execFileSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script], {
    stdio: 'inherit',
  });

  // Convert PFX → PEM via openssl if present, else via certutil + manual extract with Node.
  try {
    execFileSync(
      'openssl',
      ['pkcs12', '-in', pfx, '-nodes', '-passin', 'pass:parkio-csrf-lab', '-out', join(outDir, 'lab-all.pem')],
      { stdio: 'pipe' },
    );
    const all = readFileSync(join(outDir, 'lab-all.pem'), 'utf8');
    const keyMatch = all.match(/-----BEGIN PRIVATE KEY-----[\s\S]+?-----END PRIVATE KEY-----/);
    const certMatch = all.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/);
    if (!keyMatch || !certMatch) throw new Error('openssl pkcs12 parse failed');
    writeFileSync(keyPath, keyMatch[0] + '\n');
    writeFileSync(certPath, certMatch[0] + '\n');
    return;
  } catch {
    // Fall through to keytool/openssl-free path using Python if available, else fail with guidance.
  }

  // Use JDK keytool + a tiny Java helper to dump PEM (no openssl required).
  const javaHome = process.env.JAVA_HOME;
  if (!javaHome) throw new Error('JAVA_HOME required to export lab certs on Windows without openssl');
  const helper = join(outDir, 'PfxToPem.java');
  writeFileSync(
    helper,
    `
import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Enumeration;
public class PfxToPem {
  public static void main(String[] a) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of(a[0]))) {
      ks.load(in, a[1].toCharArray());
    }
    String alias = null;
    Enumeration<String> e = ks.aliases();
    while (e.hasMoreElements()) { alias = e.nextElement(); break; }
    PrivateKey key = (PrivateKey) ks.getKey(alias, a[1].toCharArray());
    Certificate cert = ks.getCertificate(alias);
    Files.writeString(Path.of(a[2]), pem("PRIVATE KEY", key.getEncoded()));
    Files.writeString(Path.of(a[3]), pem("CERTIFICATE", cert.getEncoded()));
  }
  static String pem(String type, byte[] der) {
    String b64 = Base64.getMimeEncoder(64, new byte[]{'\\n'}).encodeToString(der);
    return "-----BEGIN " + type + "-----\\n" + b64 + "\\n-----END " + type + "-----\\n";
  }
}
`,
  );
  execFileSync(join(javaHome, 'bin', 'javac'), [helper], { cwd: outDir, stdio: 'inherit' });
  execFileSync(
    join(javaHome, 'bin', 'java'),
    ['-cp', outDir, 'PfxToPem', pfx, 'parkio-csrf-lab', keyPath, certPath],
    { stdio: 'inherit' },
  );
}
