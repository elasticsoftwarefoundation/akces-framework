/*
 * Copyright 2022 - 2025 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.gdpr;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public final class EncryptingGDPRContext implements GDPRContext {
    private static final Logger logger = LoggerFactory.getLogger(EncryptingGDPRContext.class);
    private final String aggregateId;
    private final Cipher encryptingCipher;
    private final Cipher decryptingCipher;
    private final byte[] encryptionKey;

    public EncryptingGDPRContext(@Nonnull String aggregateId, @Nonnull byte[] encryptionKey, boolean aggregateIsUUID) {
        if (encryptionKey.length != 32) {
            throw new IllegalArgumentException("Key size needs to be 32 bytes");
        }
        this.aggregateId = aggregateId;
        this.encryptionKey = encryptionKey;
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
        IvParameterSpec ivParameterSpec = null;
        String aesMode = "ECB";
        // use cbc mode if the aggregateId is a UUID, with UUID bytes as IV
        if (aggregateIsUUID) {
            UUID aggregateUUID = UUID.fromString(aggregateId);
            ivParameterSpec = new IvParameterSpec(ByteBuffer.wrap(new byte[16]).putLong(aggregateUUID.getMostSignificantBits()).putLong(aggregateUUID.getLeastSignificantBits()).array());
            aesMode = "CBC";
        }
        try {
            encryptingCipher = Cipher.getInstance("AES/" + aesMode + "/PKCS5PADDING");
            decryptingCipher = Cipher.getInstance("AES/" + aesMode + "/PKCS5PADDING");
            encryptingCipher.init(Cipher.ENCRYPT_MODE, keySpec, ivParameterSpec, GDPRKeyUtils.secureRandom());
            decryptingCipher.init(Cipher.DECRYPT_MODE, keySpec, ivParameterSpec, GDPRKeyUtils.secureRandom());
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new SerializationException(e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    @Nullable
    public String encrypt(@Nullable String data) {
        if (data == null) {
            return null;
        }
        try {
            logger.trace("Encrypting data for aggregateId '{}' with algorithm {} and encryptionKey (hash) {}",
                    aggregateId,
                    encryptingCipher.getAlgorithm(),
                    HexFormat.of().formatHex(encryptionKey));
            return Base64.getUrlEncoder().encodeToString(encryptingCipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    @Nullable
    public String decrypt(@Nullable String encryptedData) {
        if (encryptedData == null) {
            return null;
        }
        try {
            if (encryptedData.length() % 4 == 0) {
                byte[] encryptedBytes = Base64.getUrlDecoder().decode(encryptedData);
                // needs to be multiples of 16
                if (encryptedBytes.length % 16 == 0) {
                    logger.trace("Decrypting data for aggregateId '{}' with algorithm {} and encryptionKey (hash) {}",
                            aggregateId,
                            decryptingCipher.getAlgorithm(),
                            HexFormat.of().formatHex(encryptionKey));
                    return new String(decryptingCipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
                }
            }
        } catch (IllegalArgumentException e) {
            // string size implies encryption but it is an unencrypted string
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            throw new SerializationException(e);
        }
        // data was not encrypted
        return encryptedData;
    }

    @Override
    @Nonnull
    public String getAggregateId() {
        return aggregateId;
    }

    @Nullable
    @Override
    public byte[] getEncryptionKey() {
        return encryptionKey;
    }
}
