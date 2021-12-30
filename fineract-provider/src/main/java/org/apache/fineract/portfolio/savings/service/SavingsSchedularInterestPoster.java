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
package org.apache.fineract.portfolio.savings.service;

import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.SecureRandom;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import org.apache.fineract.batch.command.CommandStrategyProvider;
import org.apache.fineract.batch.domain.BatchResponse;
import org.apache.fineract.batch.exception.ErrorHandler;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.batch.service.ResolutionHelper;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountSummaryData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountTransactionData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author manoj
 */

@Component
@Scope("prototype")
public class SavingsSchedularInterestPoster implements Callable<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsSchedularInterestPoster.class);
    private static final SecureRandom random = new SecureRandom();

    private Collection<SavingsAccountData> savingAccounts;
    private SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private SavingsAccountRepositoryWrapper savingsAccountRepository;
    private SavingsAccountAssembler savingAccountAssembler;
    private FineractPlatformTenant tenant;
    private ConfigurationDomainService configurationDomainService;
    private SavingsAccountReadPlatformServiceImpl savingsAccountReadPlatformService;
    private boolean backdatedTxnsAllowedTill;
    private List<SavingsAccountData> savingsAccountDataList = new ArrayList<>();
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private CommandStrategyProvider strategyProvider;
    private ResolutionHelper resolutionHelper;

    public void setSavings(Collection<SavingsAccountData> savingAccounts) {
        this.savingAccounts = savingAccounts;
    }

    public void setBackdatedTxnsAllowedTill(final boolean backdatedTxnsAllowedTill) {
        this.backdatedTxnsAllowedTill = backdatedTxnsAllowedTill;
    }

    public void setSavingsAccountWritePlatformService(SavingsAccountWritePlatformService savingsAccountWritePlatformService) {
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
    }

    public void setSavingsAccountRepository(SavingsAccountRepositoryWrapper savingsAccountRepository) {
        this.savingsAccountRepository = savingsAccountRepository;
    }

    public void setSavingAccountAssembler(SavingsAccountAssembler savingAccountAssembler) {
        this.savingAccountAssembler = savingAccountAssembler;
    }

    public void setTenant(FineractPlatformTenant tenant) {
        this.tenant = tenant;
    }

    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    public void setResolutionHelper(ResolutionHelper resolutionHelper) {
        this.resolutionHelper = resolutionHelper;
    }

    public void setStrategyProvider(CommandStrategyProvider commandStrategyProvider) {
        this.strategyProvider = commandStrategyProvider;
    }

    @Override
    @SuppressFBWarnings(value = {
            "DMI_RANDOM_USED_ONLY_ONCE" }, justification = "False positive for random object created and used only once")
    public Void call() throws org.apache.fineract.infrastructure.jobs.exception.JobExecutionException {
        ThreadLocalContextUtil.setTenant(tenant);
        Integer maxNumberOfRetries = tenant.getConnection().getMaxRetriesOnDeadlock();
        Integer maxIntervalBetweenRetries = tenant.getConnection().getMaxIntervalBetweenRetries();

        List<BatchResponse> responseList = new ArrayList<>();

        try {
            this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {

                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {

                    try {
                        List<Throwable> errors = new ArrayList<>();
                        int i = 0;
                        if (!savingAccounts.isEmpty()) {
                            for (SavingsAccountData savingsAccountData : savingAccounts) {
                                // LOG.info("Savings ID {}", savingsAccountData.getId());
                                Integer numberOfRetries = 0;
                                while (numberOfRetries <= maxNumberOfRetries) {
                                    try {
                                        boolean postInterestAsOn = false;
                                        LocalDate transactionDate = null;
                                        SavingsAccountData savingsAccountDataRet = savingsAccountWritePlatformService.postInterest(
                                                savingsAccountData, postInterestAsOn, transactionDate, backdatedTxnsAllowedTill);
                                        savingsAccountDataList.add(savingsAccountDataRet);

                                        numberOfRetries = maxNumberOfRetries + 1;
                                    } catch (CannotAcquireLockException | ObjectOptimisticLockingFailureException exception) {
                                        LOG.info("Interest posting job for savings ID {} has been retried {} time(s)",
                                                savingsAccountData.getId(), numberOfRetries);
                                        // Fail if the transaction has been retired for
                                        // maxNumberOfRetries
                                        if (numberOfRetries >= maxNumberOfRetries) {
                                            LOG.error(
                                                    "Interest posting job for savings ID {} has been retried for the max allowed attempts of {} and will be rolled back",
                                                    savingsAccountData.getId(), numberOfRetries);
                                            errors.add(exception);
                                            break;
                                        }
                                        // Else sleep for a random time (between 1 to 10
                                        // seconds) and continue
                                        try {
                                            int randomNum = random.nextInt(maxIntervalBetweenRetries + 1);
                                            Thread.sleep(1000 + (randomNum * 1000));
                                            numberOfRetries = numberOfRetries + 1;
                                        } catch (InterruptedException e) {
                                            LOG.error("Interest posting job for savings retry failed due to InterruptedException", e);
                                            errors.add(e);
                                            break;
                                        }
                                    } catch (Exception e) {
                                        LOG.error("Interest posting job for savings failed for account {}", savingsAccountData.getId(), e);
                                        numberOfRetries = maxNumberOfRetries + 1;
                                        errors.add(e);
                                    }
                                }
                                i++;
                                LOG.info("Savings count {}", i);
                            }

                            if (errors.isEmpty()) {
                                try {
                                    LOG.info("Batch Update Started for a thread!");
                                    LOG.info("Max Savings Id: {}", savingsAccountDataList.get(savingsAccountDataList.size() - 1).getId());
                                    LOG.info("Savings Total Count: {}", savingsAccountDataList.size());
                                    long start = System.currentTimeMillis();
                                    batchUpdate(savingsAccountDataList);
                                    long finish = System.currentTimeMillis();
                                    LOG.info("Batch Update finished  within {} milliseconds", finish - start);
                                } catch (DataAccessException exception) {
                                    LOG.error("Batch update failed due to DataAccessException", exception);
                                    errors.add(exception);
                                }
                            }

                            if (!errors.isEmpty()) {
                                throw new JobExecutionException(errors);
                            }
                        }
                    } catch (RuntimeException ex) {
                        ErrorInfo e = ErrorHandler.handler(ex);
                        BatchResponse errResponse = new BatchResponse();
                        errResponse.setStatusCode(e.getStatusCode());
                        errResponse.setBody(e.getMessage());

                        // List<BatchResponse> errResponseList = new ArrayList<>();
                        // errResponseList.add(errResponse);
                        status.setRollbackOnly();
                    } catch (JobExecutionException ex) {
                        status.setRollbackOnly();
                    }
                }
            });
        } catch (TransactionException ex) {
            ErrorInfo e = ErrorHandler.handler(ex);
            BatchResponse errResponse = new BatchResponse();
            errResponse.setStatusCode(e.getStatusCode());

            for (BatchResponse res : responseList) {
                if (!res.getStatusCode().equals(200)) {
                    errResponse.setBody("Transaction is being rolled back. First erroneous request: \n" + new Gson().toJson(res));
                    break;
                }
            }

            // List<BatchResponse> errResponseList = new ArrayList<>();
            // errResponseList.add(errResponse);

        } catch (final NonTransientDataAccessException ex) {
            ErrorInfo e = ErrorHandler.handler(ex);
            BatchResponse errResponse = new BatchResponse();
            errResponse.setStatusCode(e.getStatusCode());

            for (BatchResponse res : responseList) {
                if (!res.getStatusCode().equals(200)) {
                    errResponse.setBody("Transaction is being rolled back. First erroneous request: \n" + new Gson().toJson(res));
                    break;
                }
            }
            // List<BatchResponse> errResponseList = new ArrayList<>();
            // errResponseList.add(errResponse);
        }

        return null;
    }

    @SuppressWarnings("unused")
    private void batchUpdate(final List<SavingsAccountData> savingsAccountDataList) throws DataAccessException {
        // String queryForTransactionUpdate = batchQueryForTransactionUpdate();
        String queryForSavingsUpdate = batchQueryForSavingsSummaryUpdate();
        String queryForTransactionInsertion = batchQueryForTransactionInsertion();
        // List<Object[]> paramsForTransactionUpdates = new ArrayList<>();
        List<Object[]> paramsForTransactionInsertion = new ArrayList<>();
        List<Object[]> paramsForSavingsSummary = new ArrayList<>();
        for (SavingsAccountData savingsAccountData : savingsAccountDataList) {
            SavingsAccountSummaryData savingsAccountSummaryData = savingsAccountData.getSummary();
            paramsForSavingsSummary.add(new Object[] { savingsAccountSummaryData.getTotalDeposits(),
                    savingsAccountSummaryData.getTotalWithdrawals(), savingsAccountSummaryData.getTotalInterestEarned(),
                    savingsAccountSummaryData.getTotalInterestPosted(), savingsAccountSummaryData.getTotalWithdrawalFees(),
                    savingsAccountSummaryData.getTotalFeeCharge(), savingsAccountSummaryData.getTotalPenaltyCharge(),
                    savingsAccountSummaryData.getTotalAnnualFees(), savingsAccountSummaryData.getAvailableBalance(),
                    savingsAccountSummaryData.getTotalOverdraftInterestDerived(), savingsAccountSummaryData.getTotalWithholdTax(),
                    Date.from(savingsAccountSummaryData.getLastInterestCalculationDate().atStartOfDay(DateUtils.getDateTimeZoneOfTenant())
                            .toInstant()),
                    Date.from(savingsAccountSummaryData.getInterestPostedTillDate().atStartOfDay(DateUtils.getDateTimeZoneOfTenant())
                            .toInstant()),
                    savingsAccountData.getId() });
            List<SavingsAccountTransactionData> savingsAccountTransactionDataList = savingsAccountData.getSavingsAccountTransactionData();
            for (SavingsAccountTransactionData savingsAccountTransactionData : savingsAccountTransactionDataList) {
                if (savingsAccountTransactionData.getId() == null) {
                    java.util.Date balanceEndDate = null;
                    if (savingsAccountTransactionData.getBalanceEndDate() != null) {
                        balanceEndDate = Date.from(savingsAccountTransactionData.getBalanceEndDate()
                                .atStartOfDay(DateUtils.getDateTimeZoneOfTenant()).toInstant());
                    }
                    paramsForTransactionInsertion.add(new Object[] { savingsAccountData.getId(), savingsAccountData.getOfficeId(),
                            savingsAccountTransactionData.getTransactionType().getId(),
                            Date.from(savingsAccountTransactionData.getTransactionDate().atStartOfDay(DateUtils.getDateTimeZoneOfTenant())
                                    .toInstant()),
                            savingsAccountTransactionData.getAmount(), balanceEndDate,
                            savingsAccountTransactionData.getBalanceNumberOfDays(), savingsAccountTransactionData.getRunningBalance(),
                            savingsAccountTransactionData.getCumulativeBalance(), savingsAccountTransactionData.getSubmittedOnDate(),
                            Integer.valueOf(1), savingsAccountTransactionData.isManualTransaction() });
                }
                // else {
                // java.util.Date balanceEndDate = null;
                // if (savingsAccountTransactionData.getBalanceEndDate() != null) {
                // balanceEndDate = Date.from(savingsAccountTransactionData.getBalanceEndDate()
                // .atStartOfDay(DateUtils.getDateTimeZoneOfTenant()).toInstant());
                // }
                // paramsForTransactionUpdates.add(new Object[] { savingsAccountData.getOfficeId(),
                // savingsAccountTransactionData.getTransactionType().getId(),
                // savingsAccountTransactionData.getAmount(),
                // balanceEndDate, savingsAccountTransactionData.getBalanceNumberOfDays(),
                // savingsAccountTransactionData.getRunningBalance(),
                // savingsAccountTransactionData.getCumulativeBalance(),
                // Integer.valueOf(1), savingsAccountTransactionData.isManualTransaction(),
                // savingsAccountTransactionData.getId() });
                // }
            }
        }

        this.jdbcTemplate.batchUpdate(queryForSavingsUpdate, paramsForSavingsSummary);
        // this.jdbcTemplate.batchUpdate(queryForTransactionUpdate, paramsForTransactionUpdates);
        this.jdbcTemplate.batchUpdate(queryForTransactionInsertion, paramsForTransactionInsertion);
    }

    // private String batchQueryForTransactionUpdate() {
    // StringBuilder query = new StringBuilder(100);
    // query.append("UPDATE m_savings_account_transaction set office_id=?, is_reversed=0, transaction_type_enum=?, ");
    // query.append("amount=?, balance_end_date_derived=?, balance_number_of_days_derived=?, running_balance_derived=?,
    // ");
    // query.append("cumulative_balance_derived=?, appuser_id=?, is_manual=?, is_loan_disbursement=0 where id=?");
    // return query.toString();
    // }

    private String batchQueryForTransactionInsertion() {
        StringBuilder query = new StringBuilder(100);
        query.append("INSERT INTO m_savings_account_transaction (savings_account_id, office_id, is_reversed,");
        query.append("transaction_type_enum, transaction_date, amount, balance_end_date_derived,");
        query.append("balance_number_of_days_derived, running_balance_derived, cumulative_balance_derived,");
        query.append("created_date, appuser_id, is_manual, is_loan_disbursement) VALUES ");
        query.append("(?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)");
        return query.toString();

    }

    private String batchQueryForSavingsSummaryUpdate() {
        StringBuilder query = new StringBuilder(100);
        query.append("update m_savings_account set total_deposits_derived=?, total_withdrawals_derived=?, ");
        query.append("total_interest_earned_derived=?, total_interest_posted_derived=?, total_withdrawal_fees_derived=?, ");
        query.append("total_fees_charge_derived=?, total_penalty_charge_derived=?, total_annual_fees_derived=?, ");
        query.append("account_balance_derived=?, total_overdraft_interest_derived=?, total_withhold_tax_derived=?, ");
        query.append("last_interest_calculation_date=?, interest_posted_till_date=? where id=?");
        return query.toString();
    }

    // private SavingsAccount createSavingsAccountFromData(final SavingsAccountData savingsAccountData, final
    // Collection<SavingsAccountTransactionData> savingsAccountTransactions) {
    // List<SavingsAccountTransaction> savingsAccountTransactionList = new ArrayList<>();
    // SavingsAccount savingsAccount =
    // for (SavingsAccountTransactionData savingsAccountTransactionData: savingsAccountTransactions) {
    // savingsAccountTransactionList.add(SavingsAccountTransaction.from(savingsAccountTransactionData.getTransactionType().getId().intValue(),
    // savingsAccountTransactionData.getTransactionDate(), savingsAccountTransactionData.getAmount(),
    // savingsAccountTransactionData.isReversed(),
    // savingsAccountTransactionData.getRunningBalance(), savingsAccountTransactionData.getCumulativeBalance(),
    // savingsAccountTransactionData.getBalanceEndDate(), null, null,
    // savingsAccountTransactionData.getSubmittedOnDate(), false, null));
    // }
    // }
}
