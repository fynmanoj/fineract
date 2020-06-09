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

import org.apache.fineract.infrastructure.crypt.domain.EncryptionKeys;
import org.apache.fineract.infrastructure.crypt.data.CryptData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * @author manoj
 */

@Service
public class CryptographyReadPlatformServiceImpl  implements CryptographyReadPlatformService{
    private final EncryptionKeyStoreService encryptionKeyStore;


    @Autowired
    public CryptographyReadPlatformServiceImpl(EncryptionKeyStoreService encryptionKeyStore) {
        this.encryptionKeyStore = encryptionKeyStore;

    }


    @Override
    public CryptData getPublicRsaKey(String type){
        EncryptionKeys keys = this.encryptionKeyStore.retrieveKey(type);
        return CryptData.instance(Base64.getEncoder().encodeToString(keys.getPublicKey()), keys.getVersion());
    }
}
