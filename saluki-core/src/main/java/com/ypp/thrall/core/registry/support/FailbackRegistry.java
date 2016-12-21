/*
 * Copyright (c) 2016, Quancheng-ec.com All right reserved. This software is the
 * confidential and proprietary information of Quancheng-ec.com ("Confidential
 * Information"). You shall not disclose such Confidential Information and shall
 * use it only in accordance with the terms of the license agreement you entered
 * into with Quancheng-ec.com.
 */
package com.ypp.thrall.core.registry.support;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.ypp.thrall.core.common.Constants;
import com.ypp.thrall.core.common.NamedThreadFactory;
import com.ypp.thrall.core.common.ThrallURL;
import com.ypp.thrall.core.registry.NotifyListener;

/**
 * @author shimingliu 2016年12月14日 下午1:55:42
 * @version FailBackRegistry1.java, v 0.0.1 2016年12月14日 下午1:55:42 shimingliu
 */
public abstract class FailbackRegistry extends AbstractRegistry {

    // 定时任务执行器
    private final ScheduledExecutorService                                       retryExecutor      = Executors.newScheduledThreadPool(1,
                                                                                                                                       new NamedThreadFactory("SalukiRegistryFailedRetryTimer",
                                                                                                                                                              true));
    // 失败重试定时器，定时检查是否有请求失败，如有，无限次重试
    private final ScheduledFuture<?>                                             retryFuture;

    private final Set<ThrallURL>                                                 failedRegistered   = Sets.newConcurrentHashSet();

    private final Set<ThrallURL>                                                 failedUnregistered = Sets.newConcurrentHashSet();

    private final ConcurrentMap<ThrallURL, Set<NotifyListener>>                  failedSubscribed   = Maps.newConcurrentMap();

    private final ConcurrentMap<ThrallURL, Set<NotifyListener>>                  failedUnsubscribed = Maps.newConcurrentMap();

    private final ConcurrentMap<ThrallURL, Map<NotifyListener, List<ThrallURL>>> failedNotified     = Maps.newConcurrentMap();

    public FailbackRegistry(ThrallURL url){
        super(url);
        int retryPeriod = url.getParameter(Constants.REGISTRY_RETRY_PERIOD_KEY,
                                           Constants.DEFAULT_REGISTRY_RETRY_PERIOD);
        this.retryFuture = retryExecutor.scheduleWithFixedDelay(new Runnable() {

            public void run() {
                // 检测并连接注册中心
                try {
                    retry();
                } catch (Throwable t) { // 防御性容错
                    logger.error("Unexpected error occur at failed retry, cause: " + t.getMessage(), t);
                }
            }
        }, retryPeriod, retryPeriod, TimeUnit.MILLISECONDS);
    }

    private void retry() {
        if (!failedRegistered.isEmpty()) {
            Set<ThrallURL> failed = new HashSet<ThrallURL>(failedRegistered);
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry register " + failed);
                }
                try {
                    for (ThrallURL url : failed) {
                        try {
                            doRegister(url);
                            failedRegistered.remove(url);
                        } catch (Throwable t) { // 忽略所有异常，等待下次重试
                            logger.warn("Failed to retry register " + failed + ", waiting for again, cause: "
                                        + t.getMessage(), t);
                        }
                    }
                } catch (Throwable t) { // 忽略所有异常，等待下次重试
                    logger.warn("Failed to retry register " + failed + ", waiting for again, cause: " + t.getMessage(),
                                t);
                }
            }
        }
        if (!failedUnregistered.isEmpty()) {
            Set<ThrallURL> failed = new HashSet<ThrallURL>(failedUnregistered);
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry unregister " + failed);
                }
                try {
                    for (ThrallURL url : failed) {
                        try {
                            doUnregister(url);
                            failedUnregistered.remove(url);
                        } catch (Throwable t) { // 忽略所有异常，等待下次重试
                            logger.warn("Failed to retry unregister  " + failed + ", waiting for again, cause: "
                                        + t.getMessage(), t);
                        }
                    }
                } catch (Throwable t) { // 忽略所有异常，等待下次重试
                    logger.warn("Failed to retry unregister  " + failed + ", waiting for again, cause: "
                                + t.getMessage(), t);
                }
            }
        }
        if (!failedSubscribed.isEmpty()) {
            Map<ThrallURL, Set<NotifyListener>> failed = new HashMap<ThrallURL, Set<NotifyListener>>(failedSubscribed);
            for (Map.Entry<ThrallURL, Set<NotifyListener>> entry : new HashMap<ThrallURL, Set<NotifyListener>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry subscribe " + failed);
                }
                try {
                    for (Map.Entry<ThrallURL, Set<NotifyListener>> entry : failed.entrySet()) {
                        ThrallURL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            try {
                                doSubscribe(url, listener);
                                listeners.remove(listener);
                            } catch (Throwable t) { // 忽略所有异常，等待下次重试
                                logger.warn("Failed to retry subscribe " + failed + ", waiting for again, cause: "
                                            + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // 忽略所有异常，等待下次重试
                    logger.warn("Failed to retry subscribe " + failed + ", waiting for again, cause: " + t.getMessage(),
                                t);
                }
            }
        }
        if (!failedUnsubscribed.isEmpty()) {
            Map<ThrallURL, Set<NotifyListener>> failed = new HashMap<ThrallURL, Set<NotifyListener>>(failedUnsubscribed);
            for (Map.Entry<ThrallURL, Set<NotifyListener>> entry : new HashMap<ThrallURL, Set<NotifyListener>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry unsubscribe " + failed);
                }
                try {
                    for (Map.Entry<ThrallURL, Set<NotifyListener>> entry : failed.entrySet()) {
                        ThrallURL url = entry.getKey();
                        Set<NotifyListener> listeners = entry.getValue();
                        for (NotifyListener listener : listeners) {
                            try {
                                doUnsubscribe(url, listener);
                                listeners.remove(listener);
                            } catch (Throwable t) { // 忽略所有异常，等待下次重试
                                logger.warn("Failed to retry unsubscribe " + failed + ", waiting for again, cause: "
                                            + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // 忽略所有异常，等待下次重试
                    logger.warn("Failed to retry unsubscribe " + failed + ", waiting for again, cause: "
                                + t.getMessage(), t);
                }
            }
        }
        if (!failedNotified.isEmpty()) {
            Map<ThrallURL, Map<NotifyListener, List<ThrallURL>>> failed = new HashMap<ThrallURL, Map<NotifyListener, List<ThrallURL>>>(failedNotified);
            for (Map.Entry<ThrallURL, Map<NotifyListener, List<ThrallURL>>> entry : new HashMap<ThrallURL, Map<NotifyListener, List<ThrallURL>>>(failed).entrySet()) {
                if (entry.getValue() == null || entry.getValue().size() == 0) {
                    failed.remove(entry.getKey());
                }
            }
            if (failed.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Retry notify " + failed);
                }
                try {
                    for (Map<NotifyListener, List<ThrallURL>> values : failed.values()) {
                        for (Map.Entry<NotifyListener, List<ThrallURL>> entry : values.entrySet()) {
                            try {
                                NotifyListener listener = entry.getKey();
                                List<ThrallURL> urls = entry.getValue();
                                listener.notify(urls);
                                values.remove(listener);
                            } catch (Throwable t) { // 忽略所有异常，等待下次重试
                                logger.warn("Failed to retry notify " + failed + ", waiting for again, cause: "
                                            + t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) { // 忽略所有异常，等待下次重试
                    logger.warn("Failed to retry notify " + failed + ", waiting for again, cause: " + t.getMessage(),
                                t);
                }
            }
        }
    }

    public Future<?> getRetryFuture() {
        return retryFuture;
    }

    public Set<ThrallURL> getFailedRegistered() {
        return failedRegistered;
    }

    public Set<ThrallURL> getFailedUnregistered() {
        return failedUnregistered;
    }

    public Map<ThrallURL, Set<NotifyListener>> getFailedSubscribed() {
        return failedSubscribed;
    }

    public Map<ThrallURL, Set<NotifyListener>> getFailedUnsubscribed() {
        return failedUnsubscribed;
    }

    public Map<ThrallURL, Map<NotifyListener, List<ThrallURL>>> getFailedNotified() {
        return failedNotified;
    }

    private void addFailedSubscribed(ThrallURL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners == null) {
            listeners = Sets.newConcurrentHashSet();
            listeners = failedSubscribed.putIfAbsent(url, listeners);
        }
        listeners.add(listener);
    }

    private void removeFailedSubscribed(ThrallURL url, NotifyListener listener) {
        Set<NotifyListener> listeners = failedSubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        listeners = failedUnsubscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
        Map<NotifyListener, List<ThrallURL>> notified = failedNotified.get(url);
        if (notified != null) {
            notified.remove(listener);
        }
    }

    @Override
    public void register(ThrallURL url) {
        super.register(url);
        failedRegistered.remove(url);
        failedUnregistered.remove(url);
        try {
            // 向服务器端发送注册请求
            doRegister(url);
        } catch (Exception e) {
            logger.error("Failed to uregister " + url + ", waiting for retry, cause: " + e.getMessage(), e);
            // 将失败的取消注册请求记录到失败列表，定时重试
            failedUnregistered.add(url);
        }
    }

    @Override
    public void unregister(ThrallURL url) {
        super.unregister(url);
        failedRegistered.remove(url);
        failedUnregistered.remove(url);
        try {
            // 向服务器端发送取消注册请求
            doUnregister(url);
        } catch (Exception e) {
            logger.error("Failed to uregister " + url + ", waiting for retry, cause: " + e.getMessage(), e);
            // 将失败的取消注册请求记录到失败列表，定时重试
            failedUnregistered.add(url);
        }
    }

    @Override
    public void subscribe(ThrallURL url, NotifyListener listener) {
        super.subscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            // 向服务器端发送订阅请求
            doSubscribe(url, listener);
        } catch (Exception e) {
            logger.error("Failed to subscribe " + url + ", waiting for retry, cause: " + e.getMessage(), e);
            // 将失败的订阅请求记录到失败列表，定时重试
            addFailedSubscribed(url, listener);
        }
    }

    @Override
    public void unsubscribe(ThrallURL url, NotifyListener listener) {
        super.unsubscribe(url, listener);
        removeFailedSubscribed(url, listener);
        try {
            // 向服务器端发送取消订阅请求
            doUnsubscribe(url, listener);
        } catch (Exception e) {
            logger.error("Failed to unsubscribe " + url + ", waiting for retry, cause: " + e.getMessage(), e);
            // 将失败的取消订阅请求记录到失败列表，定时重试
            Set<NotifyListener> listeners = failedUnsubscribed.get(url);
            if (listeners == null) {
                listeners = Sets.newConcurrentHashSet();
                listeners = failedUnsubscribed.putIfAbsent(url, listeners);
            }
            listeners.add(listener);
        }
    }

    @Override
    protected void notify(ThrallURL url, NotifyListener listener, List<ThrallURL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        try {
            doNotify(url, listener, urls);
        } catch (Exception t) {
            // 将失败的通知请求记录到失败列表，定时重试
            Map<NotifyListener, List<ThrallURL>> listeners = failedNotified.get(url);
            if (listeners == null) {
                failedNotified.putIfAbsent(url, Maps.newConcurrentMap());
                listeners = failedNotified.get(url);
            }
            listeners.put(listener, urls);
            logger.error("Failed to notify for subscribe " + url + ", waiting for retry, cause: " + t.getMessage(), t);
        }
    }

    protected void doNotify(ThrallURL url, NotifyListener listener, List<ThrallURL> urls) {
        super.notify(url, listener, urls);
    }

    @Override
    protected void recover() throws Exception {
        // register
        Set<ThrallURL> recoverRegistered = Sets.newHashSet(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (ThrallURL url : recoverRegistered) {
                failedRegistered.add(url);
            }
        }
        // subscribe
        Map<ThrallURL, Set<NotifyListener>> recoverSubscribed = Maps.newHashMap(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<ThrallURL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                ThrallURL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    // ==== 模板方法 ====
    protected abstract void doRegister(ThrallURL url);

    protected abstract void doUnregister(ThrallURL url);

    protected abstract void doSubscribe(ThrallURL url, NotifyListener listener);

    protected abstract void doUnsubscribe(ThrallURL url, NotifyListener listener);
}