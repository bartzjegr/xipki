/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
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

package org.xipki.litecaclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.cmp.CMPCertificate;
import org.bouncycastle.asn1.cmp.CMPObjectIdentifiers;
import org.bouncycastle.asn1.cmp.CertRepMessage;
import org.bouncycastle.asn1.cmp.CertResponse;
import org.bouncycastle.asn1.cmp.CertifiedKeyPair;
import org.bouncycastle.asn1.cmp.ErrorMsgContent;
import org.bouncycastle.asn1.cmp.GenMsgContent;
import org.bouncycastle.asn1.cmp.GenRepContent;
import org.bouncycastle.asn1.cmp.InfoTypeAndValue;
import org.bouncycastle.asn1.cmp.PKIBody;
import org.bouncycastle.asn1.cmp.PKIHeader;
import org.bouncycastle.asn1.cmp.PKIMessage;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.cmp.PKIStatusInfo;
import org.bouncycastle.asn1.cmp.RevDetails;
import org.bouncycastle.asn1.cmp.RevRepContent;
import org.bouncycastle.asn1.cmp.RevReqContent;
import org.bouncycastle.asn1.crmf.AttributeTypeAndValue;
import org.bouncycastle.asn1.crmf.CertId;
import org.bouncycastle.asn1.crmf.CertReqMessages;
import org.bouncycastle.asn1.crmf.CertReqMsg;
import org.bouncycastle.asn1.crmf.CertRequest;
import org.bouncycastle.asn1.crmf.CertTemplateBuilder;
import org.bouncycastle.asn1.crmf.EncryptedValue;
import org.bouncycastle.asn1.crmf.POPOSigningKey;
import org.bouncycastle.asn1.crmf.ProofOfPossession;
import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.cmp.GeneralPKIMessage;
import org.bouncycastle.cert.cmp.ProtectedPKIMessage;
import org.bouncycastle.cert.cmp.ProtectedPKIMessageBuilder;
import org.bouncycastle.cert.crmf.ProofOfPossessionSigningKeyBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO.
 * @author Lijun Liao
 */

public abstract class CmpCaClient {

  private static final Logger LOG = LoggerFactory.getLogger(CmpCaClient.class);

  private static final String CMP_REQUEST_MIMETYPE = "application/pkixcmp";

  private static final String CMP_RESPONSE_MIMETYPE = "application/pkixcmp";

  private static final ASN1ObjectIdentifier id_xipki_cmp =
      new ASN1ObjectIdentifier("1.3.6.2.4.1.45522.2.2");

  private final URL caUrl;

  private final String caUri;

  private final String hashAlgo;

  private final GeneralName requestorSubject;

  protected final GeneralName responderSubject;

  private final SecureRandom random;

  private X509Certificate caCert;

  private byte[] caSubjectKeyIdentifier;

  private X500Name caSubject;

  public CmpCaClient(String caUri, X509Certificate caCert, X500Name requestorSubject,
      X500Name responderSubject, String hashAlgo) throws Exception {
    this.caUri = SdkUtil.requireNonBlank("caUri", caUri);
    this.caUrl = new URL(this.caUri);
    this.hashAlgo = (hashAlgo == null) ? "SHA256" : hashAlgo;

    this.random = new SecureRandom();

    this.requestorSubject = new GeneralName(requestorSubject);
    this.responderSubject = new GeneralName(responderSubject);

    if (caCert != null) {
      this.caCert = caCert;
      this.caSubject = X500Name.getInstance(caCert.getSubjectX500Principal().getEncoded());
      this.caSubjectKeyIdentifier = SdkUtil.extractSki(caCert);
    }
  }

  public void init() throws Exception {
    TlsInit.init();

    if (caCert != null) {
      return;
    }

    Certificate tcert = cmpCaCerts()[0];
    this.caSubject = tcert.getSubject();
    this.caCert = SdkUtil.parseCert(tcert.getEncoded());
    this.caSubjectKeyIdentifier = SdkUtil.extractSki(this.caCert);
  }

  public void shutdown() {
    TlsInit.shutdown();
  }

  public X509Certificate getCaCert() {
    return caCert;
  }

  private byte[] randomTransactionId() {
    byte[] bytes = new byte[20];
    random.nextBytes(bytes);
    return bytes;
  }

  private byte[] randomSenderNonce() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return bytes;
  }

  protected abstract ProtectedPKIMessage build(ProtectedPKIMessageBuilder builder) throws Exception;

  private Certificate[] cmpCaCerts() throws Exception {
    ProtectedPKIMessageBuilder builder = new ProtectedPKIMessageBuilder(
        PKIHeader.CMP_2000, requestorSubject, responderSubject);
    builder.setMessageTime(new Date());
    builder.setTransactionID(randomTransactionId());
    builder.setSenderNonce(randomSenderNonce());

    InfoTypeAndValue itv = new InfoTypeAndValue(id_xipki_cmp);
    PKIBody body = new PKIBody(PKIBody.TYPE_GEN_MSG, new GenMsgContent(itv));
    builder.setBody(body);

    ProtectedPKIMessage request = build(builder);
    PKIMessage response = transmit(request, null);
    ASN1Encodable asn1Value = extractGeneralRepContent(response, id_xipki_cmp.getId());
    ASN1Sequence seq = ASN1Sequence.getInstance(asn1Value);

    final int size = seq.size();
    Certificate[] caCerts = new Certificate[size];
    for (int i = 0; i < size; i++) {
      caCerts[i] = CMPCertificate.getInstance(seq.getObjectAt(i)).getX509v3PKCert();
    }
    return caCerts;
  }

  private ASN1Encodable extractGeneralRepContent(PKIMessage response,
      String expectedType) throws Exception {
    PKIBody respBody = response.getBody();
    int bodyType = respBody.getType();

    if (PKIBody.TYPE_ERROR == bodyType) {
      ErrorMsgContent content = ErrorMsgContent.getInstance(respBody.getContent());
      throw new Exception("Server returned PKIStatus: " + buildText(content.getPKIStatusInfo()));
    } else if (PKIBody.TYPE_GEN_REP != bodyType) {
      throw new Exception(String.format("unknown PKI body type %s instead the expected [%s, %s]",
          bodyType, PKIBody.TYPE_GEN_REP, PKIBody.TYPE_ERROR));
    }

    GenRepContent genRep = GenRepContent.getInstance(respBody.getContent());

    InfoTypeAndValue[] itvs = genRep.toInfoTypeAndValueArray();
    InfoTypeAndValue itv = null;
    if (itvs != null && itvs.length > 0) {
      for (InfoTypeAndValue entry : itvs) {
        if (expectedType.equals(entry.getInfoType().getId())) {
          itv = entry;
          break;
        }
      }
    }
    if (itv == null) {
      throw new Exception("the response does not contain InfoTypeAndValue " + expectedType);
    }

    return itv.getInfoValue();
  } // method extractGeneralRepContent

  protected abstract boolean verifyProtection(GeneralPKIMessage pkiMessage) throws Exception;

  private PKIMessage transmit(ProtectedPKIMessage request, String uri) throws Exception {
    byte[] encodedResponse = send(request.toASN1Structure().getEncoded(), uri);
    GeneralPKIMessage response = new GeneralPKIMessage(encodedResponse);

    PKIHeader reqHeader = request.getHeader();
    PKIHeader respHeader = response.getHeader();
    ASN1OctetString tid = reqHeader.getTransactionID();
    if (!tid.equals(respHeader.getTransactionID())) {
      throw new Exception("response.transactionId != request.transactionId");
    }

    ASN1OctetString senderNonce = reqHeader.getSenderNonce();
    if (!senderNonce.equals(respHeader.getRecipNonce())) {
      throw new Exception("response.recipientNonce != request.senderNonce");
    }

    GeneralName rec = respHeader.getRecipient();
    if (!requestorSubject.equals(rec)) {
      throw new Exception("unknown CMP requestor " + rec.toString());
    }

    if (!response.hasProtection()) {
      PKIBody respBody = response.getBody();
      int bodyType = respBody.getType();
      if (bodyType != PKIBody.TYPE_ERROR) {
        throw new Exception("response is not signed");
      } else {
        return response.toASN1Structure();
      }
    }

    if (verifyProtection(response)) {
      return response.toASN1Structure();
    }

    throw new Exception("invalid signature/MAC in PKI protection");
  }

  private Map<BigInteger, KeyAndCert> parseEnrollCertResult(PKIMessage response, int numCerts)
      throws Exception {
    PKIBody respBody = response.getBody();
    final int bodyType = respBody.getType();

    if (PKIBody.TYPE_ERROR == bodyType) {
      ErrorMsgContent content = ErrorMsgContent.getInstance(respBody.getContent());
      throw new Exception("Server returned PKIStatus: " + buildText(content.getPKIStatusInfo()));
    } else if (PKIBody.TYPE_CERT_REP != bodyType) {
      throw new Exception(String.format("unknown PKI body type %s instead the expected [%s, %s]",
          bodyType, PKIBody.TYPE_CERT_REP, PKIBody.TYPE_ERROR));
    }

    CertRepMessage certRep = CertRepMessage.getInstance(respBody.getContent());
    CertResponse[] certResponses = certRep.getResponse();

    if (certResponses.length != numCerts) {
      throw new Exception("expected " + numCerts + " CertResponse, but returned "
          + certResponses.length);
    }

    // We only accept the certificates which are requested.
    Map<BigInteger, KeyAndCert> keycerts = new HashMap<>(numCerts * 2);
    for (int i = 0; i < numCerts; i++) {
      CertResponse certResp = certResponses[i];
      PKIStatusInfo statusInfo = certResp.getStatus();
      int status = statusInfo.getStatus().intValue();
      BigInteger certReqId = certResp.getCertReqId().getValue();

      if (status != PKIStatus.GRANTED && status != PKIStatus.GRANTED_WITH_MODS) {
        throw new Exception("CertReqId " + certReqId
            + ": server returned PKIStatus: " + buildText(statusInfo));
      }

      CertifiedKeyPair cvk = certResp.getCertifiedKeyPair();
      if (cvk != null) {
        CMPCertificate cmpCert = cvk.getCertOrEncCert().getCertificate();
        X509Certificate cert = SdkUtil.parseCert(cmpCert.getX509v3PKCert().getEncoded());
        if (!verify(caCert, cert)) {
          throw new Exception("CertReqId " + certReqId
              + ": the returned certificate is not issued by the given CA");
        }

        EncryptedValue encKey = cvk.getPrivateKey();
        PrivateKeyInfo key = null;
        if (encKey != null) {
          byte[] keyBytes = decrypt(encKey);
          key = PrivateKeyInfo.getInstance(keyBytes);
        }

        keycerts.put(certReqId, new KeyAndCert(key, cert));
      }
    }

    return keycerts;
  } // method parseEnrollCertResult

  public X509Certificate requestCertViaCsr(String certprofile, CertificationRequest csr,
      boolean profileInUri) throws Exception {
    ProtectedPKIMessageBuilder builder = new ProtectedPKIMessageBuilder(
        PKIHeader.CMP_2000, requestorSubject, responderSubject);
    builder.setMessageTime(new Date());
    builder.setTransactionID(randomTransactionId());
    builder.setSenderNonce(randomSenderNonce());

    builder.addGeneralInfo(
        new InfoTypeAndValue(CMPObjectIdentifiers.it_implicitConfirm, DERNull.INSTANCE));
    String uri = null;
    if (profileInUri) {
      uri = caUri + "?certprofile=" + certprofile.toLowerCase();
    } else {
      builder.addGeneralInfo(
          new InfoTypeAndValue(CMPObjectIdentifiers.regInfo_utf8Pairs,
              new DERUTF8String("certprofile?" + certprofile + "%")));
    }
    builder.setBody(new PKIBody(PKIBody.TYPE_P10_CERT_REQ, csr));
    ProtectedPKIMessage request = build(builder);

    PKIMessage response = transmit(request, uri);
    return parseEnrollCertResult(response, 1).values().iterator().next().getCert();
  }

  private boolean parseRevocationResult(PKIMessage response, BigInteger serialNumber)
      throws Exception {
    PKIBody respBody = response.getBody();
    final int bodyType = respBody.getType();

    if (PKIBody.TYPE_ERROR == bodyType) {
      ErrorMsgContent content = ErrorMsgContent.getInstance(respBody.getContent());
      throw new Exception("Server returned PKIStatus: " + content.getPKIStatusInfo());
    } else if (PKIBody.TYPE_REVOCATION_REP != bodyType) {
      throw new Exception(String.format(
          "unknown PKI body type %s instead the expected [%s, %s]", bodyType,
          PKIBody.TYPE_REVOCATION_REP, PKIBody.TYPE_ERROR));
    }

    RevRepContent content = RevRepContent.getInstance(respBody.getContent());
    PKIStatusInfo[] statuses = content.getStatus();
    int statusesLen = (statuses == null) ? 0 : statuses.length;
    if (statusesLen != 1) {
      throw new Exception(String.format("incorrect number of status entries in response '%s'"
          + " instead the expected '1'", statusesLen));
    }

    PKIStatusInfo statusInfo = statuses[0];
    int status = statusInfo.getStatus().intValue();

    if (status != PKIStatus.GRANTED && status != PKIStatus.GRANTED_WITH_MODS) {
      LOG.warn("Server returned error: " + buildText(statusInfo));
      return false;
    }

    CertId[] revCerts = content.getRevCerts();
    if (revCerts == null) {
      return true;
    }

    CertId revCert = revCerts[0];
    return caSubject.equals(revCert.getIssuer().getName())
        && serialNumber.equals(revCert.getSerialNumber().getValue());
  }

  public X509Certificate requestCertViaCrmf(String certprofile, PrivateKey privateKey,
      SubjectPublicKeyInfo publicKeyInfo, String subject, boolean profileInUri)
          throws Exception {
    return requestCertViaCrmf(new String[]{certprofile},
        new PrivateKey[]{privateKey},
        new SubjectPublicKeyInfo[] {publicKeyInfo},
        new String[] {subject},
        profileInUri)[0];
  }

  public X509Certificate[] requestCertViaCrmf(String[] certprofiles, PrivateKey[] privateKey,
      SubjectPublicKeyInfo[] publicKeyInfo, String[] subject, boolean profileInUri)
          throws Exception {
    final int n = certprofiles.length;

    String uri = null;
    if (profileInUri) {
      if (n > 1) {
        for (int i = 1; i < n; i++) {
          if (!certprofiles[0].equalsIgnoreCase(certprofiles[i])) {
            throw new IllegalArgumentException("not all certprofiles are of the same");
          }
        }
      }
      uri = caUri + "?certprofile=" + certprofiles[0];
    }

    CertReqMsg[] certReqMsgs = new CertReqMsg[n];
    BigInteger[] certReqIds = new BigInteger[n];

    for (int i = 0; i < n; i++) {
      certReqIds[i] = BigInteger.valueOf(i + 1);

      CertTemplateBuilder certTemplateBuilder = new CertTemplateBuilder();
      certTemplateBuilder.setSubject(new X500Name(subject[i]));
      certTemplateBuilder.setPublicKey(publicKeyInfo[i]);
      CertRequest certReq = new CertRequest(new ASN1Integer(certReqIds[i]),
          certTemplateBuilder.build(), null);
      ProofOfPossessionSigningKeyBuilder popoBuilder
          = new ProofOfPossessionSigningKeyBuilder(certReq);
      ContentSigner popoSigner = buildSigner(privateKey[i]);
      POPOSigningKey popoSk = popoBuilder.build(popoSigner);
      ProofOfPossession popo = new ProofOfPossession(popoSk);

      AttributeTypeAndValue[] atvs = null;
      if (uri == null) {
        AttributeTypeAndValue certprofileInfo =
            new AttributeTypeAndValue(CMPObjectIdentifiers.regInfo_utf8Pairs,
                new DERUTF8String("certprofile?" + certprofiles[i] + "%"));
        atvs = new AttributeTypeAndValue[]{certprofileInfo};
      }
      certReqMsgs[i] = new CertReqMsg(certReq, popo, atvs);
    }

    PKIBody body = new PKIBody(PKIBody.TYPE_CERT_REQ, new CertReqMessages(certReqMsgs));
    ProtectedPKIMessageBuilder builder = new ProtectedPKIMessageBuilder(
        PKIHeader.CMP_2000, requestorSubject, responderSubject);
    builder.setMessageTime(new Date());
    builder.setTransactionID(randomTransactionId());
    builder.setSenderNonce(randomSenderNonce());

    builder.addGeneralInfo(
        new InfoTypeAndValue(CMPObjectIdentifiers.it_implicitConfirm, DERNull.INSTANCE));
    builder.setBody(body);

    ProtectedPKIMessage request = build(builder);
    PKIMessage response = transmit(request, uri);
    Map<BigInteger, KeyAndCert> keyCerts = parseEnrollCertResult(response, n);

    X509Certificate[] ret = new X509Certificate[n];
    for (int i = 0; i < n; i++) {
      BigInteger certReqId = certReqIds[i];
      ret[i] = keyCerts.get(certReqId).getCert();
    }

    return ret;
  } // method requestCerts

  public KeyAndCert requestCertViaCrmf(String certprofile, String genkeyType,
      String subject, boolean profileAndKeyTypeInUri) throws Exception {
    return requestCertViaCrmf(new String[]{certprofile}, new String[]{genkeyType},
        new String[] {subject}, profileAndKeyTypeInUri)[0];
  }

  public KeyAndCert[] requestCertViaCrmf(String[] certprofiles, String[] genkeyTypes,
      String[] subject, boolean profileAndKeyTypeInUri) throws Exception {
    final int n = certprofiles.length;

    String uri = null;
    if (profileAndKeyTypeInUri) {
      if (n > 1) {
        for (int i = 1; i < n; i++) {
          if (!certprofiles[0].equalsIgnoreCase(certprofiles[i])) {
            throw new IllegalArgumentException("not all certprofiles are of the same");
          }
        }
      }

      final int ng = genkeyTypes.length;
      if (ng > 1) {
        for (int i = 1; i < ng; i++) {
          if (!genkeyTypes[0].equalsIgnoreCase(genkeyTypes[i])) {
            throw new IllegalArgumentException("not all genkeyTypes are of the same");
          }
        }
      }

      uri = caUri + "?certprofile=" + certprofiles[0] + "&generatekey=" + genkeyTypes[0];
    }

    CertReqMsg[] certReqMsgs = new CertReqMsg[n];
    BigInteger[] certReqIds = new BigInteger[n];

    for (int i = 0; i < n; i++) {
      certReqIds[i] = BigInteger.valueOf(i + 1);

      CertTemplateBuilder certTemplateBuilder = new CertTemplateBuilder();
      certTemplateBuilder.setSubject(new X500Name(subject[i]));
      CertRequest certReq = new CertRequest(new ASN1Integer(certReqIds[i]),
          certTemplateBuilder.build(), null);

      AttributeTypeAndValue[] atvs = null;
      if (uri == null) {
        String utf8pairs =
            "certprofile?" + certprofiles[i] + "%generatekey?" + genkeyTypes[i] + "%";
        AttributeTypeAndValue certprofileInfo =
            new AttributeTypeAndValue(CMPObjectIdentifiers.regInfo_utf8Pairs,
                new DERUTF8String(utf8pairs));
        atvs = new AttributeTypeAndValue[]{certprofileInfo};
      }

      certReqMsgs[i] = new CertReqMsg(certReq, null, atvs);
    }

    PKIBody body = new PKIBody(PKIBody.TYPE_CERT_REQ, new CertReqMessages(certReqMsgs));
    ProtectedPKIMessageBuilder builder = new ProtectedPKIMessageBuilder(
        PKIHeader.CMP_2000, requestorSubject, responderSubject);
    builder.setMessageTime(new Date());
    builder.setTransactionID(randomTransactionId());
    builder.setSenderNonce(randomSenderNonce());

    builder.addGeneralInfo(
        new InfoTypeAndValue(CMPObjectIdentifiers.it_implicitConfirm, DERNull.INSTANCE));
    builder.setBody(body);

    ProtectedPKIMessage request = build(builder);
    PKIMessage response = transmit(request, uri);
    Map<BigInteger, KeyAndCert> keyCerts = parseEnrollCertResult(response, n);

    KeyAndCert[] ret = new KeyAndCert[n];
    for (int i = 0; i < n; i++) {
      BigInteger certReqId = certReqIds[i];
      ret[i] = keyCerts.get(certReqId);
    }

    return ret;
  } // method requestCerts

  public boolean revokeCert(BigInteger serialNumber, CRLReason reason) throws Exception {
    ProtectedPKIMessageBuilder builder = new ProtectedPKIMessageBuilder(
        PKIHeader.CMP_2000, requestorSubject, responderSubject);
    builder.setMessageTime(new Date());
    builder.setTransactionID(randomTransactionId());
    builder.setSenderNonce(randomSenderNonce());

    CertTemplateBuilder certTempBuilder = new CertTemplateBuilder();
    certTempBuilder.setIssuer(caSubject);
    certTempBuilder.setSerialNumber(new ASN1Integer(serialNumber));

    AuthorityKeyIdentifier aki = new AuthorityKeyIdentifier(caSubjectKeyIdentifier);
    byte[] encodedAki = aki.getEncoded();

    Extension extAki = new Extension(Extension.authorityKeyIdentifier, false, encodedAki);
    Extensions certTempExts = new Extensions(extAki);
    certTempBuilder.setExtensions(certTempExts);

    ASN1Enumerated asn1Reason = new ASN1Enumerated(reason.getValue().intValue());
    Extensions exts = new Extensions(
        new Extension(Extension.reasonCode, true, new DEROctetString(asn1Reason.getEncoded())));
    RevDetails revDetails = new RevDetails(certTempBuilder.build(), exts);

    RevReqContent content = new RevReqContent(revDetails);
    builder.setBody(new PKIBody(PKIBody.TYPE_REVOCATION_REQ, content));
    ProtectedPKIMessage request = build(builder);

    PKIMessage response = transmit(request, null);
    return parseRevocationResult(response, serialNumber);
  }

  private boolean verify(X509Certificate caCert, X509Certificate cert) {
    if (!cert.getIssuerX500Principal().equals(caCert.getSubjectX500Principal())) {
      return false;
    }

    PublicKey caPublicKey = caCert.getPublicKey();
    try {
      cert.verify(caPublicKey);
      return true;
    } catch (Exception ex) {
      LOG.debug("{} while verifying signature: {}", ex.getClass().getName(), ex.getMessage());
      return false;
    }
  } // method verify

  public boolean unrevokeCert(BigInteger serialNumber) throws Exception {
    return revokeCert(serialNumber, CRLReason.lookup(CRLReason.removeFromCRL));
  }

  public byte[] send(byte[] request, String uri) throws IOException {
    SdkUtil.requireNonNull("request", request);

    URL url = (uri == null) ? caUrl : new URL(uri);
    HttpURLConnection httpUrlConnection = SdkUtil.openHttpConn(url);
    httpUrlConnection.setDoOutput(true);
    httpUrlConnection.setUseCaches(false);

    httpUrlConnection.setRequestMethod("POST");
    httpUrlConnection.setRequestProperty("Content-Type", CMP_REQUEST_MIMETYPE);
    httpUrlConnection.setRequestProperty("Content-Length", Integer.toString(request.length));
    OutputStream outputstream = httpUrlConnection.getOutputStream();
    outputstream.write(request);
    outputstream.flush();

    InputStream inputStream = httpUrlConnection.getInputStream();
    if (httpUrlConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      inputStream.close();
      throw new IOException("bad response: " + httpUrlConnection.getResponseCode() + "    "
          + httpUrlConnection.getResponseMessage());
    }
    String responseContentType = httpUrlConnection.getContentType();
    boolean isValidContentType = false;
    if (responseContentType != null) {
      if (responseContentType.equalsIgnoreCase(CMP_RESPONSE_MIMETYPE)) {
        isValidContentType = true;
      }
    }
    if (!isValidContentType) {
      inputStream.close();
      throw new IOException("bad response: mime type " + responseContentType + " not supported!");
    }

    return SdkUtil.read(inputStream);
  } // method send

  protected abstract byte[] decrypt(EncryptedValue ev) throws Exception;

  private static String buildText(PKIStatusInfo pkiStatusInfo) {
    final int status = pkiStatusInfo.getStatus().intValue();
    switch (status) {
      case 0: return "accepted (0)";
      case 1: return "grantedWithMods (1)";
      case 2: return "rejection (2)";
      case 3: return "waiting (3)";
      case 4: return "revocationWarning (4)";
      case 5: return "revocationNotification (5)";
      case 6: return "keyUpdateWarning (6)";
      default: return Integer.toString(status);
    }
  }

  protected ContentSigner buildSigner(PrivateKey signingKey) throws OperatorCreationException {
    String keyAlgo = signingKey.getAlgorithm().toUpperCase();
    String sigAlgo = "EC".equals(keyAlgo) ? hashAlgo + "WITHECDSA" : hashAlgo + "WITH" + keyAlgo;
    return new JcaContentSignerBuilder(sigAlgo).build(signingKey);
  }

}
