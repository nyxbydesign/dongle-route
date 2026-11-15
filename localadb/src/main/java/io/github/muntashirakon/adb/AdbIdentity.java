// SPDX-License-Identifier: Apache-2.0
package io.github.muntashirakon.adb;

import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

/** A single app-owned identity. Caller supplies an app-private, non-backed-up directory. */
public final class AdbIdentity {
    private final AtomicFile keyFile;
    private final AtomicFile certificateFile;

    public AdbIdentity(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Identity directory unavailable");
        keyFile = new AtomicFile(new File(directory, "identity.pk8"));
        certificateFile = new AtomicFile(new File(directory, "identity.crt"));
    }

    public synchronized void ensureExists() throws IOException, GeneralSecurityException {
        boolean hasKey = keyFile.getBaseFile().exists();
        boolean hasCertificate = certificateFile.getBaseFile().exists();
        if (hasKey != hasCertificate) throw new IOException("Incomplete ADB identity; explicitly forget and pair again");
        if (hasKey) { loadPrivateKey(); loadCertificate(); return; }
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, random);
        java.security.KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=DongleRoute local ADB");
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        V3TBSCertificateGenerator tbsGenerator = new V3TBSCertificateGenerator();
        tbsGenerator.setSerialNumber(new ASN1Integer(new BigInteger(159, random).add(BigInteger.ONE)));
        tbsGenerator.setSignature(algorithm);
        tbsGenerator.setIssuer(name);
        tbsGenerator.setSubject(name);
        long now = System.currentTimeMillis();
        tbsGenerator.setStartDate(new Time(new Date(now - 86400000L)));
        tbsGenerator.setEndDate(new Time(new Date(now + 10L * 365 * 86400000L)));
        tbsGenerator.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        TBSCertificate tbs = tbsGenerator.generateTBSCertificate();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate(), random);
        signer.update(tbs.getEncoded(ASN1Encoding.DER));
        byte[] cert = new DERSequence(new ASN1Encodable[]{tbs, algorithm, new DERBitString(signer.sign())}).getEncoded(ASN1Encoding.DER);
        byte[] key = pair.getPrivate().getEncoded();
        try {
            write(keyFile, key);
            write(certificateFile, cert);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public synchronized PrivateKey loadPrivateKey() throws IOException, GeneralSecurityException {
        byte[] encoded = read(keyFile, 16384);
        try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded)); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    public synchronized Certificate loadCertificate() throws IOException, GeneralSecurityException {
        byte[] encoded = read(certificateFile, 32768);
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }

    public synchronized void verifyPair(PrivateKey key, Certificate certificate) throws GeneralSecurityException {
        if (!(key instanceof RSAPrivateCrtKey) || !(certificate.getPublicKey() instanceof RSAPublicKey)
                || !((RSAPrivateCrtKey) key).getModulus().equals(((RSAPublicKey) certificate.getPublicKey()).getModulus())) {
            throw new GeneralSecurityException("ADB identity does not match certificate");
        }
        certificate.verify(certificate.getPublicKey());
    }

    public synchronized void forget() throws IOException {
        keyFile.delete(); certificateFile.delete();
        if (keyFile.getBaseFile().exists() || certificateFile.getBaseFile().exists()) throw new IOException("Cannot remove ADB identity");
    }

    private static byte[] read(AtomicFile file, int limit) throws IOException {
        if (file.getBaseFile().length() <= 0 || file.getBaseFile().length() > limit) throw new IOException("Invalid ADB identity file");
        byte[] data = file.readFully();
        if (data.length > limit) throw new IOException("ADB identity exceeds size limit");
        return data;
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = file.startWrite();
        try { output.write(bytes); file.finishWrite(output); }
        catch (IOException failure) { file.failWrite(output); throw failure; }
    }
}
