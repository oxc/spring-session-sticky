/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.session.sticky.config.annotation.web.http;

import static org.springframework.session.sticky.StickySessionRepository.DEFAULT_CLEANUP_AFTER_MINUTES;
import static org.springframework.session.sticky.StickySessionRepository.DEFAULT_REVALIDATE_AFTER_SECONDS;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportAware;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.SessionRepository;
import org.springframework.session.sticky.StickySessionRepository;
import org.springframework.session.sticky.StickySessionRepositoryAdapter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Configures a {@link StickySessionRepository} as a {@link Primary} session repository.
 *
 * There is no annotation yet to configure this class, you'll have to extend it.
 *
 * @author Bernhard Frauendienst
 */
@Configuration(proxyBeanMethods = false)
public class StickyHttpSessionConfiguration implements ImportAware {

  public static final String DEFAULT_CACHE_CLEANUP_CRON = "0 */5 * * * *";

  public static final int DEFAULT_CACHE_CONCURRENCY = 16;

  private ApplicationEventPublisher eventPublisher;

  private FlushMode flushMode = FlushMode.ON_SAVE;

  private SaveMode saveMode = SaveMode.ON_SET_ATTRIBUTE;

  private String cacheCleanupCron = DEFAULT_CACHE_CLEANUP_CRON;

  private int cacheConcurrency = DEFAULT_CACHE_CONCURRENCY;

  private @Nullable Duration revalidateAfter = Duration.ofSeconds(DEFAULT_REVALIDATE_AFTER_SECONDS);

  private Duration cleanupAfter = Duration.ofMinutes(DEFAULT_CLEANUP_AFTER_MINUTES);

  private @Nullable Executor asyncSaveExecutor;

  private boolean asyncSaveExecutorConfigured = false;

  @Autowired
  public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
    this.eventPublisher = applicationEventPublisher;
  }

  public void setFlushMode(FlushMode flushMode) {
    Assert.notNull(flushMode, "flushMode cannot be null");
    this.flushMode = flushMode;
  }

  public void setSaveMode(SaveMode saveMode) {
    Assert.notNull(saveMode, "saveMode cannot be null");
    this.saveMode = saveMode;
  }

  public void setCacheCleanupCron(String cacheCleanupCron) {
    this.cacheCleanupCron = cacheCleanupCron;
  }

  public void setCacheConcurrency(int cacheConcurrency) {
    this.cacheConcurrency = cacheConcurrency;
  }

  public void setRevalidateAfter(@Nullable Duration revalidateAfter) {
    this.revalidateAfter = revalidateAfter;
  }

  public void setCleanupAfter(Duration cleanupAfter) {
    Assert.notNull(cleanupAfter, "cleanupAfter cannot be null");
    this.cleanupAfter = cleanupAfter;
  }

  public void setAsyncSaveExecutor(@Nullable Executor asyncSaveExecutor) {
    this.asyncSaveExecutor = asyncSaveExecutor;
    this.asyncSaveExecutorConfigured = true;
  }

  private Executor createDefaultAsyncSaveExecutor() {
    return Executors.newFixedThreadPool(16);
  }

  @Primary
  @Bean
  public StickySessionRepository<?> stickySessionRepository(
      StickySessionRepositoryAdapter<? extends SessionRepository<?>> stickySessionRepositoryAdapter) {
    @SuppressWarnings("rawtypes") // if we add a type parameter, this bean won't get autowired
        // might be solved with https://github.com/spring-projects/spring-framework/issues/24965
    StickySessionRepository<?> sessionRepository = new StickySessionRepository(stickySessionRepositoryAdapter, this.cacheConcurrency);

    sessionRepository.setFlushMode(this.flushMode);
    sessionRepository.setSaveMode(this.saveMode);

    sessionRepository.setApplicationEventPublisher(this.eventPublisher);
    if (this.asyncSaveExecutorConfigured) {
      sessionRepository.setAsyncSaveExecutor(this.asyncSaveExecutor);
    } else {
      this.asyncSaveExecutor = createDefaultAsyncSaveExecutor();
    }
    sessionRepository.setRevalidateAfter(this.revalidateAfter);
    sessionRepository.setCleanupAfter(this.cleanupAfter);
    return sessionRepository;
  }

  @Override
  public void setImportMetadata(AnnotationMetadata importMetadata) {
    Map<String, Object> attributeMap = importMetadata
        .getAnnotationAttributes(EnableStickyHttpSession.class.getName());
    AnnotationAttributes attributes = AnnotationAttributes.fromMap(attributeMap);
    int revalidateAfterSeconds = attributes.getNumber("revalidateAfterSeconds");
    this.revalidateAfter = revalidateAfterSeconds >= 0 ? Duration.ofSeconds(revalidateAfterSeconds) : null;

    int cleanupAfterMinutes = attributes.getNumber("cleanupAfterMinutes");
    if (cleanupAfterMinutes > 0) {
      this.cleanupAfter = Duration.ofMinutes(cleanupAfterMinutes);
    }

    int asyncSaveThreads = attributes.getNumber("asyncSaveThreads");
    if (asyncSaveThreads >= 0) {
      setAsyncSaveExecutor(asyncSaveThreads > 0 ? Executors.newFixedThreadPool(asyncSaveThreads) : null);
    }

    this.flushMode = attributes.getEnum("flushMode");
    this.saveMode = attributes.getEnum("saveMode");
    String cacheCleanupCron = attributes.getString("cacheCleanupCron");
    if (StringUtils.hasText(cacheCleanupCron)) {
      this.cacheCleanupCron = cacheCleanupCron;
    }
  }


  /**
   * Configuration of scheduled job for cleaning up outdated cache entries.
   */
  @EnableScheduling
  @Configuration(proxyBeanMethods = false)
  class SessionCleanupConfiguration implements SchedulingConfigurer {

    private final StickySessionRepository<?> sessionRepository;

    SessionCleanupConfiguration(StickySessionRepository<?> sessionRepository) {
      this.sessionRepository = sessionRepository;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
      taskRegistrar.addCronTask(this.sessionRepository::cleanupOutdatedCacheEntries,
          StickyHttpSessionConfiguration.this.cacheCleanupCron);
    }

  }

}