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
package org.apache.fineract.infrastructure.crypt.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_encryption_keys")
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class EncryptionKey extends AbstractPersistableCustom {

    @Column(name = "key_type", nullable = false)
    private String keyType;

    @Lob
    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Lob
    @Column(name = "private_key", nullable = false)
    private byte[] privateKey;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}