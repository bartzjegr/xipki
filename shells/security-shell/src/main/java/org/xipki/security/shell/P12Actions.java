/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.security.shell;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.completers.FileCompleter;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.xipki.security.ConcurrentContentSigner;
import org.xipki.security.EdECConstants;
import org.xipki.security.HashAlgo;
import org.xipki.security.SignatureAlgoControl;
import org.xipki.security.SignerConf;
import org.xipki.security.XiSecurityException;
import org.xipki.security.pkcs12.KeypairWithCert;
import org.xipki.security.pkcs12.KeystoreGenerationParameters;
import org.xipki.security.pkcs12.P12KeyGenerationResult;
import org.xipki.security.pkcs12.P12KeyGenerator;
import org.xipki.security.shell.Actions.CsrGenAction;
import org.xipki.security.shell.Actions.SecurityAction;
import org.xipki.security.util.KeyUtil;
import org.xipki.security.util.X509Util;
import org.xipki.shell.CmdFailure;
import org.xipki.shell.Completers;
import org.xipki.shell.IllegalCmdParamException;
import org.xipki.util.Args;
import org.xipki.util.ConfPairs;
import org.xipki.util.ObjectCreationException;
import org.xipki.util.StringUtil;

/**
 * TODO.
 * @author Lijun Liao
 */

public class P12Actions {

  @Command(scope = "xi", name = "secretkey-p12",
      description = "generate secret key in JCEKS (not PKCS#12) keystore")
  @Service
  public static class SecretkeyP12 extends P12KeyGenAction {

    @Option(name = "--key-type", required = true,
        description = "keytype, current only AES, DES3 and GENERIC are supported")
    @Completion(SecurityCompleters.SecretKeyTypeCompleter.class)
     private String keyType;

    @Option(name = "--key-size", required = true, description = "keysize in bit")
    private Integer keysize;

    @Override
    protected Object execute0() throws Exception {
      if (!("AES".equalsIgnoreCase(keyType) || "DES3".equalsIgnoreCase(keyType)
            || "GENERIC".equalsIgnoreCase(keyType))) {
        throw new IllegalCmdParamException("invalid keyType " + keyType);
      }

      P12KeyGenerationResult key = new P12KeyGenerator().generateSecretKey(
          keyType.toUpperCase(), keysize, getKeyGenParameters());
      saveKey(key);

      return null;
    }

  }

  @Command(scope = "xi", name = "export-cert-p12",
      description = "export certificate from PKCS#12 keystore")
  @Service
  public static class ExportCertP12 extends P12SecurityAction {

    @Option(name = "--outform", description = "output format of the certificate")
    @Completion(Completers.DerPemCompleter.class)
    protected String outform = "der";

    @Option(name = "--out", aliases = "-o", required = true,
        description = "where to save the certificate")
    @Completion(FileCompleter.class)
    private String outFile;

    @Override
    protected Object execute0() throws Exception {
      KeyStore ks = getKeyStore();

      String keyname = null;
      Enumeration<String> aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (ks.isKeyEntry(alias)) {
          keyname = alias;
          break;
        }
      }

      if (keyname == null) {
        throw new CmdFailure("could not find private key");
      }

      X509Certificate cert = (X509Certificate) ks.getCertificate(keyname);
      saveVerbose("saved certificate to file", outFile, encodeCert(cert.getEncoded(), outform));

      return null;
    }

  }

  @Command(scope = "xi", name = "update-cert-p12",
      description = "update certificate in PKCS#12 keystore")
  @Service
  public static class UpdateCertP12 extends P12SecurityAction {

    @Option(name = "--cert", required = true, description = "certificate file")
    @Completion(FileCompleter.class)
    private String certFile;

    @Option(name = "--ca-cert", multiValued = true, description = "CA Certificate file")
    @Completion(FileCompleter.class)
    private Set<String> caCertFiles;

    @Override
    protected Object execute0() throws Exception {
      KeyStore ks = getKeyStore();

      char[] pwd = getPassword();
      X509Certificate newCert = X509Util.parseCert(new File(certFile));

      assertMatch(ks, newCert, new String(pwd));

      String keyname = null;
      Enumeration<String> aliases = ks.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (ks.isKeyEntry(alias)) {
          keyname = alias;
          break;
        }
      }

      if (keyname == null) {
        throw new XiSecurityException("could not find private key");
      }

      Key key = ks.getKey(keyname, pwd);
      Set<X509Certificate> caCerts = new HashSet<>();
      if (isNotEmpty(caCertFiles)) {
        for (String caCertFile : caCertFiles) {
          caCerts.add(X509Util.parseCert(new File(caCertFile)));
        }
      }
      X509Certificate[] certChain = X509Util.buildCertPath(newCert, caCerts);
      ks.setKeyEntry(keyname, key, pwd, certChain);

      try (OutputStream out = Files.newOutputStream(Paths.get(p12File))) {
        ks.store(out, pwd);
        println("updated certificate");
        return null;
      }
    }

    private void assertMatch(KeyStore ks, X509Certificate cert, String password)
        throws Exception {
      String keyAlgName = cert.getPublicKey().getAlgorithm();
      if (EdECConstants.ALG_X25519.equalsIgnoreCase(keyAlgName)
          || EdECConstants.ALG_X448.equalsIgnoreCase(keyAlgName)) {
        // cannot be checked via creating dummy signature, just compare the public keys
        char[] pwd = password.toCharArray();
        KeypairWithCert kp = KeypairWithCert.fromKeystore(ks, null, pwd, (X509Certificate[]) null);
        byte[] expectedEncoded = kp.getPublicKey().getEncoded();
        byte[] encoded = cert.getPublicKey().getEncoded();
        if (!Arrays.equals(expectedEncoded, encoded)) {
          throw new XiSecurityException("the certificate and private do not match");
        }
      } else {
        ConfPairs pairs = new ConfPairs("keystore", "file:" + p12File);
        if (password != null) {
          pairs.putPair("password", new String(password));
        }

        SignerConf conf = new SignerConf(pairs.getEncoded(), HashAlgo.SHA256, null);
        securityFactory.createSigner("PKCS12", conf, cert);
      }
    }

  }

  @Command(scope = "xi", name = "csr-p12", description = "generate CSR with PKCS#12 keystore")
  @Service
  public static class CsrP12 extends CsrGenAction {

    @Option(name = "--p12", required = true, description = "PKCS#12 keystore file")
    @Completion(FileCompleter.class)
    private String p12File;

    @Option(name = "--password", description = "password of the PKCS#12 keystore file")
    private String password;

    private char[] getPassword() throws IOException {
      char[] pwdInChar = readPasswordIfNotSet(password);
      if (pwdInChar != null) {
        password = new String(pwdInChar);
      }
      return pwdInChar;
    }

    public KeyStore getKeyStore()
        throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
      KeyStore ks;
      try (InputStream in = Files.newInputStream(Paths.get(expandFilepath(p12File)))) {
        ks = KeyUtil.getKeyStore("PKCS12");
        ks.load(in, getPassword());
      }
      return ks;
    }

    @Override
    protected ConcurrentContentSigner getSigner(SignatureAlgoControl signatureAlgoControl)
        throws ObjectCreationException {
      Args.notNull(signatureAlgoControl, "signatureAlgoControl");
      char[] pwd;
      try {
        pwd = getPassword();
      } catch (IOException ex) {
        throw new ObjectCreationException("could not read password: " + ex.getMessage(), ex);
      }
      SignerConf conf = getKeystoreSignerConf(p12File, new String(pwd),
          HashAlgo.getNonNullInstance(hashAlgo), signatureAlgoControl, peerCertFile);
      return securityFactory.createSigner("PKCS12", conf, (X509Certificate[]) null);
    }

    static SignerConf getKeystoreSignerConf(String keystoreFile, String password,
        HashAlgo hashAlgo, SignatureAlgoControl signatureAlgoControl, String peerCertFile)
            throws ObjectCreationException {
      ConfPairs conf = new ConfPairs("password", password);
      conf.putPair("parallelism", Integer.toString(1));
      conf.putPair("keystore", "file:" + keystoreFile);
      SignerConf signerConf = new SignerConf(conf.getEncoded(), hashAlgo, signatureAlgoControl);
      if (StringUtil.isNotBlank(peerCertFile)) {
        X509Certificate cert;
        try {
          cert = X509Util.parseCert(Paths.get(peerCertFile).toFile());
        } catch (CertificateException | IOException ex) {
          throw new ObjectCreationException(ex.getMessage(), ex);
        }
        signerConf.setPeerCertificates(Arrays.asList(cert));
      }
      return signerConf;
    }

  }

  @Command(scope = "xi", name = "dsa-p12", description = "generate RSA keypair in PKCS#12 keystore")
  @Service
  public static class DsaP12 extends P12KeyGenAction {

    @Option(name = "--subject", aliases = "-s",
        description = "subject of the self-signed certificate")
    private String subject;

    @Option(name = "--plen", description = "bit length of the prime")
    private Integer plen = 2048;

    @Option(name = "--qlen", description = "bit length of the sub-prime")
    private Integer qlen;

    @Override
    protected Object execute0() throws Exception {
      if (plen % 1024 != 0) {
        throw new IllegalCmdParamException("plen is not multiple of 1024: " + plen);
      }

      if (qlen == null) {
        if (plen <= 1024) {
          qlen = 160;
        } else if (plen <= 2048) {
          qlen = 224;
        } else {
          qlen = 256;
        }
      }

      P12KeyGenerationResult keypair = new P12KeyGenerator().generateDSAKeypair(plen,
          qlen, getKeyGenParameters(), subject);
      saveKey(keypair);

      return null;
    }

  }

  @Command(scope = "xi", name = "ec-p12", description = "generate EC keypair in PKCS#12 keystore")
  @Service
  public static class EcP12 extends P12KeyGenAction {

    @Option(name = "--subject", aliases = "-s",
        description = "subject of the self-signed certificate")
    protected String subject;

    @Option(name = "--curve", description = "EC curve name or OID")
    @Completion(Completers.ECCurveNameCompleter.class)
    private String curveName = "secp256r1";

    @Override
    protected Object execute0() throws Exception {
      P12KeyGenerator keyGen = new P12KeyGenerator();
      KeystoreGenerationParameters keyGenParams = getKeyGenParameters();
      P12KeyGenerationResult keypair;
      if (EdECConstants.isEdwardsOrMontgemoryCurve(curveName)) {
        keypair = keyGen.generateEdECKeypair(curveName, keyGenParams, subject);
      } else {
        keypair = new P12KeyGenerator().generateECKeypair(curveName, keyGenParams, subject);
      }
      saveKey(keypair);

      return null;
    }

  }

  public abstract static class P12KeyGenAction extends SecurityAction {

    @Option(name = "--out", aliases = "-o", required = true, description = "where to save the key")
    @Completion(FileCompleter.class)
    protected String keyOutFile;

    @Option(name = "--password", description = "password of the keystore file")
    protected String password;

    protected void saveKey(P12KeyGenerationResult keyGenerationResult) throws IOException {
      Args.notNull(keyGenerationResult, "keyGenerationResult");
      saveVerbose("saved PKCS#12 keystore to file", keyOutFile, keyGenerationResult.keystore());
    }

    protected KeystoreGenerationParameters getKeyGenParameters() throws IOException {
      KeystoreGenerationParameters params = new KeystoreGenerationParameters(getPassword());

      SecureRandom random = securityFactory.getRandom4Key();
      if (random != null) {
        params.setRandom(random);
      }

      return params;
    }

    private char[] getPassword() throws IOException {
      char[] pwdInChar = readPasswordIfNotSet(password);
      if (pwdInChar != null) {
        password = new String(pwdInChar);
      }
      return pwdInChar;
    }

  }

  @Command(scope = "xi", name = "rsa-p12", description = "generate RSA keypair in PKCS#12 keystore")
  @Service
  public static class RsaP12 extends P12KeyGenAction {

    @Option(name = "--subject", aliases = "-s",
        description = "subject of the self-signed certificate")
    private String subject;

    @Option(name = "--key-size", description = "keysize in bit")
    private Integer keysize = 2048;

    @Option(name = "-e", description = "public exponent")
    private String publicExponent = "0x10001";

    @Override
    protected Object execute0() throws Exception {
      if (keysize % 1024 != 0) {
        throw new IllegalCmdParamException("keysize is not multiple of 1024: " + keysize);
      }

      P12KeyGenerationResult keypair = new P12KeyGenerator().generateRSAKeypair(keysize,
          toBigInt(publicExponent), getKeyGenParameters(), subject);
      saveKey(keypair);

      return null;
    }

  }

  public abstract static class P12SecurityAction extends SecurityAction {

    @Option(name = "--p12", required = true, description = "PKCS#12 keystore file")
    @Completion(FileCompleter.class)
    protected String p12File;

    @Option(name = "--password", description = "password of the PKCS#12 file")
    protected String password;

    protected char[] getPassword() throws IOException {
      char[] pwdInChar = readPasswordIfNotSet(password);
      if (pwdInChar != null) {
        password = new String(pwdInChar);
      }
      return pwdInChar;
    }

    protected KeyStore getKeyStore()
        throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException,
          NoSuchProviderException {
      KeyStore ks;
      try (InputStream in = Files.newInputStream(Paths.get(expandFilepath(p12File)))) {
        ks = KeyUtil.getKeyStore("PKCS12");
        ks.load(in, getPassword());
      }
      return ks;
    }

  }

  @Command(scope = "xi", name = "sm2-p12",
      description = "generate SM2 (curve sm2p256v1) keypair in PKCS#12 keystore")
  @Service
  public static class Sm2P12 extends P12KeyGenAction {

    @Option(name = "--subject", aliases = "-s",
        description = "subject of the self-signed certificate")
    protected String subject;

    @Override
    protected Object execute0() throws Exception {
      P12KeyGenerationResult keypair = new P12KeyGenerator().generateECKeypair(
          GMObjectIdentifiers.sm2p256v1.getId(), getKeyGenParameters(), subject);
      saveKey(keypair);

      return null;
    }

  }

}
