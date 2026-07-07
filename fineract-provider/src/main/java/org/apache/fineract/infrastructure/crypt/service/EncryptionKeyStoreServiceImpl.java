/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.crypt.service;


import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import jakarta.inject.Singleton;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.crypt.domain.EncryptionKeyPair;
import org.apache.fineract.infrastructure.crypt.utils.RSAEncryptionUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.apache.fineract.infrastructure.crypt.domain.EncryptionKey;
import org.apache.fineract.infrastructure.crypt.repository.EncryptionKeyRepository;

/**
 * @author manoj
 */
@Service
@Singleton
public class EncryptionKeyStoreServiceImpl  implements EncryptionKeyStoreService{
    private final EncryptionKeyRepository encryptionKeyRepository;


    private final ConfigurationDomainService configurationDomainService;
    private final RSAEncryptionUtils rsaEncryptionUtils;

    @Autowired
    public EncryptionKeyStoreServiceImpl(
            ConfigurationDomainService configurationDomainService,
            RSAEncryptionUtils rsaEncryptionUtils,
            EncryptionKeyRepository encryptionKeyRepository) {
        this.configurationDomainService = configurationDomainService;
        this.rsaEncryptionUtils = rsaEncryptionUtils;
        this.encryptionKeyRepository = encryptionKeyRepository;
    }


    private void storeKey(String type, EncryptionKeyPair key) {

        EncryptionKey entity = encryptionKeyRepository.findByKeyType(type)
                .orElseGet(() -> {
                    EncryptionKey newEntity = new EncryptionKey();
                    newEntity.setKeyType(type);
                    return newEntity;
                });

        entity.setPublicKey(
                Base64.getEncoder().encodeToString(key.getPublicKey()));

        entity.setPrivateKey(
                Base64.getEncoder().encodeToString(key.getPrivateKey()));
        entity.setVersion(key.getVersion());
        entity.setCreatedAt(key.getCreatedDateTime());

        encryptionKeyRepository.save(entity);
    }

    private EncryptionKeyPair retrieveValidKey(String type){

        EncryptionKeyPair keys = getKeys(type);
        if(keys == null){
            return null;
        }
        //check validityOftheKey
        //get validity time from config
        Integer validUpto = configurationDomainService.retrieveEncKeyExpirySeconds(type);
        if(validUpto.equals(-1)){
            return keys;
        } else {
            Duration seconds = Duration.between(keys.getCreatedDateTime(), DateUtils.getLocalDateTimeOfTenant());
            if(seconds.getSeconds() < validUpto) {
                return keys;
            }

        }
        return null;
    }


    private EncryptionKeyPair getKeys(String type) {

        return encryptionKeyRepository.findByKeyType(type)
                .map(entity -> new EncryptionKeyPair(
                        Base64.getDecoder().decode(entity.getPrivateKey()),
                        Base64.getDecoder().decode(entity.getPublicKey()),
                        entity.getCreatedAt(),
                        entity.getVersion()))
                .orElse(null);
    }

    @Override
    public EncryptionKeyPair retrieveKey(String type){
        EncryptionKeyPair keys = retrieveValidKey(type);
        if(null == keys) {
            //create new key pair and store
            keys = rsaEncryptionUtils.generateKeys();
            //store keys
            this.storeKey(type, keys);
            //return public key
        }
        return keys;
    }

}