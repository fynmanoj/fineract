package org.apache.fineract.portfolio.loanaccount.domain;

import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.joda.time.LocalDate;
import org.springframework.data.domain.Persistable;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Entity
@Table(name = "m_loan_arrears_aging")
public class LoanSummaryArrearsAging implements Persistable<Long> {
    @Id
    @Column(name = "loan_id", unique = true, nullable = false)
    private Long loanId;

    @Column(name = "principal_overdue_derived", scale = 6, precision = 19)
    private BigDecimal totalPrincipalOverdue;

    @Column(name = "interest_overdue_derived", scale = 6, precision = 19)
    private BigDecimal totalInterestOverdue;

    @Column(name = "fee_charges_overdue_derived", scale = 6, precision = 19)
    private BigDecimal totalFeeChargesOverdue;

    @Column(name = "penalty_charges_overdue_derived", scale = 6, precision = 19)
    private BigDecimal totalPenaltyChargesOverdue;

    @Column(name = "total_overdue_derived", scale = 6, precision = 19)
    private BigDecimal totalOverdue;

    @Temporal(TemporalType.DATE)
    @Column(name = "overdue_since_date_derived")
    private Date overdueSinceDate;

    @OneToOne(optional = false)
    @PrimaryKeyJoinColumn
    private Loan loan;

    protected LoanSummaryArrearsAging() {
        //
    }

    public LoanSummaryArrearsAging(final Loan loan) {
        this.loan = loan;
        zeroFields();
    }

    @Override
    public Long getId() {
        return this.loanId;
    }

    @Override
    public boolean isNew() {
        return null == getId();
    }

    public void zeroFields() {
        this.totalPrincipalOverdue = BigDecimal.ZERO;
        this.totalInterestOverdue = BigDecimal.ZERO;
        this.totalFeeChargesOverdue = BigDecimal.ZERO;
        this.totalPenaltyChargesOverdue = BigDecimal.ZERO;
        this.totalOverdue = BigDecimal.ZERO;
        this.overdueSinceDate = null;
    }

    public void updateSummary(final MonetaryCurrency currency, final List<LoanRepaymentScheduleInstallment> repaymentScheduleInstallments,
                              final LoanSummaryWrapper summaryWrapper) {

        this.totalPrincipalOverdue = summaryWrapper.calculateTotalPrincipalOverdueOn(repaymentScheduleInstallments, currency,
                DateUtils.getLocalDateOfTenant()).getAmount();
        this.totalInterestOverdue = summaryWrapper.calculateTotalInterestOverdueOn(repaymentScheduleInstallments, currency,
                DateUtils.getLocalDateOfTenant()).getAmount();
        this.totalFeeChargesOverdue =  summaryWrapper.calculateTotalFeeChargesOverdueOn(repaymentScheduleInstallments, currency,
                DateUtils.getLocalDateOfTenant()).getAmount();
        this.totalPenaltyChargesOverdue = summaryWrapper.calculateTotalPenaltyChargesOverdueOn(repaymentScheduleInstallments, currency,
                DateUtils.getLocalDateOfTenant()).getAmount();

        final Money totalOverdue = Money.of(currency, this.totalPrincipalOverdue).plus(this.totalInterestOverdue);
        //.plus(this.totalFeeChargesOverdue).plus(this.totalPenaltyChargesOverdue);
        this.totalOverdue = totalOverdue.getAmount();

        final LocalDate overdueSinceLocalDate = summaryWrapper.determineOverdueSinceDateFrom(repaymentScheduleInstallments, currency,
                DateUtils.getLocalDateOfTenant());
        if (overdueSinceLocalDate != null) {
            this.overdueSinceDate = overdueSinceLocalDate.toDate();
        } else {
            this.overdueSinceDate = null;
        }
    }

    public boolean isNotInArrears(final MonetaryCurrency currency) {
        return Money.of(currency, this.totalOverdue).isZero();
    }

    public LocalDate fetchOverdueSinceDate() {
        LocalDate overdueSinceDate = null;
        if (this.overdueSinceDate != null) {
            overdueSinceDate = new LocalDate(this.overdueSinceDate);
        }
        return overdueSinceDate;
    }
}
