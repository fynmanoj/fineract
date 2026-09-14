package org.apache.fineract.infrastructure.security.jobs;

import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.infrastructure.jobs.service.StepName;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PurgeExpiredUserSessionsConfig {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PurgeExpiredUserSessionsTasklet tasklet;

    @Bean
    protected Step purgeExpiredUserSessionsStep() {
        return new StepBuilder(StepName.PURGE_EXPIRED_USER_SESSIONS_STEP.name(), jobRepository).tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Job purgeExpiredUserSessionsJob() {
        return new JobBuilder(JobName.PURGE_EXPIRED_USER_SESSIONS.name(), jobRepository).start(purgeExpiredUserSessionsStep())
                .incrementer(new RunIdIncrementer()).build();
    }
}
