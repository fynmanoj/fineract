/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
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
package org.apache.fineract.portfolio.loanaccount.guarantor.domain;

/**
 * Enum representation of {@link Guarantor} approval status.
 */
public enum GuarantorApprovalStatus {

    INVALID(0, "guarantorApprovalStatus.invalid"), //
    PENDING(100, "guarantorApprovalStatus.pending"), //
    APPROVED(200, "guarantorApprovalStatus.approved"), //
    REJECTED(300, "guarantorApprovalStatus.rejected");

    private final Integer value;
    private final String code;

    public static GuarantorApprovalStatus fromInt(final Integer type) {

        GuarantorApprovalStatus enumeration = GuarantorApprovalStatus.INVALID;
        switch (type) {
            case 100:
                enumeration = GuarantorApprovalStatus.PENDING;
                break;
            case 200:
                enumeration = GuarantorApprovalStatus.APPROVED;
                break;
            case 300:
                enumeration = GuarantorApprovalStatus.REJECTED;
                break;
        }

        return enumeration;
    }

    GuarantorApprovalStatus(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public boolean hasStateOf(final GuarantorApprovalStatus state) {
        return this.value.equals(state.getValue());
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public boolean isPending() {
        return this.value.equals(GuarantorApprovalStatus.PENDING.getValue());
    }

    public boolean isApproved() {
        return this.value.equals(GuarantorApprovalStatus.APPROVED.getValue());
    }

    public boolean isRejected() {
        return this.value.equals(GuarantorApprovalStatus.REJECTED.getValue());
    }
}