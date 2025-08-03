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
package org.apache.fineract.infrastructure.crypt.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.crypt.data.PublicKeyData;
import org.apache.fineract.infrastructure.security.service.PublicKeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Path("/v1/crypt")
@Component
public class CryptographyApiResource {

    private final ToApiJsonSerializer<PublicKeyData> toApiJsonSerializer;
    private final PublicKeyService publicKeyService;

    @Autowired
    public CryptographyApiResource(ToApiJsonSerializer<PublicKeyData> toApiJsonSerializer,
                                   PublicKeyService publicKeyService) {
        this.toApiJsonSerializer = toApiJsonSerializer;
        this.publicKeyService = publicKeyService;
    }

    @GET
    @Path("publickey/{type}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    public String getRsaPublicKey(@PathParam("type") final String type) {
        return this.toApiJsonSerializer.serialize(this.publicKeyService.getPublicKey());
    }
}
