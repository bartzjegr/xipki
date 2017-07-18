/*
 *
 * Copyright (c) 2013 - 2017 Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 *
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.ocsp.server.impl.type;

import java.math.BigInteger;

import org.bouncycastle.asn1.BERTags;
import org.xipki.security.HashAlgoType;

/**
 * @author Lijun Liao
 * @since 2.2.0
 */

public class CertID extends ASN1Type {

    private final HashAlgoType hashAlgorithm;

    private final byte[] issuerNameHash;

    private final byte[] issuerKeyHash;

    private final BigInteger serialNumber;

    private final int bodyLength;

    private final int encodedLength;

    public CertID(HashAlgoType hashAlgorithm, byte[] issuerNameHash, byte[] issuerKeyHash,
            BigInteger serialNumber) {
        this.hashAlgorithm = hashAlgorithm;
        this.issuerNameHash = issuerNameHash;
        this.issuerKeyHash = issuerKeyHash;
        this.serialNumber = serialNumber;

        int algIdLen = hashAlgorithm.encodedLength() + 2; // 2 is the length of encoded DERNull

        int len = getHeaderLen(algIdLen) + algIdLen;

        len += 2 + issuerNameHash.length;
        len += 2 + issuerKeyHash.length;

        int snBytesLen = 1 + serialNumber.bitLength() / 8;
        len += getHeaderLen(snBytesLen) + snBytesLen;

        this.bodyLength = len;
        this.encodedLength = getHeaderLen(bodyLength) + bodyLength;
    }

    public HashAlgoType hashAlgorithm() {
        return hashAlgorithm;
    }

    public byte[] issuerNameHash() {
        return issuerNameHash;
    }

    public byte[] issuerKeyHash() {
        return issuerKeyHash;
    }

    public BigInteger serialNumber() {
        return serialNumber;
    }

    @Override
    public int encodedLength() {
        return encodedLength;
    }

    public int write(final byte[] out, final int offset) {
        int idx = offset;
        idx += writeHeader(BERTags.SEQUENCE | BERTags.CONSTRUCTED, bodyLength, out, idx);

        // hashAlgorithm
        int algIdLen = hashAlgorithm.encodedLength() + 2; // 2 is the length of encoded DERNull
        idx += writeHeader(BERTags.SEQUENCE | BERTags.CONSTRUCTED, algIdLen, out, idx);
        idx += hashAlgorithm.write(out, idx);
        idx += writeHeader(BERTags.NULL, 0, out, idx);

        // issuerNameHash
        out[idx++] = BERTags.OCTET_STRING;
        out[idx++] = (byte) issuerNameHash.length;
        idx += arraycopy(issuerNameHash, out, idx);

        // issuerKeyHash
        out[idx++] = BERTags.OCTET_STRING;
        out[idx++] = (byte) issuerKeyHash.length;
        idx += arraycopy(issuerKeyHash, out, idx);

        // serialNumbers
        byte[] snBytes = serialNumber.toByteArray();
        idx += writeHeader(BERTags.INTEGER, snBytes.length, out, idx);
        idx += arraycopy(snBytes, out, idx);

        return idx - offset;
    }

}