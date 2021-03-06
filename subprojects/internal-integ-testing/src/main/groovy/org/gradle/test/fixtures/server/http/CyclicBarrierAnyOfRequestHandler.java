/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.http;

import com.sun.net.httpserver.HttpExchange;
import org.gradle.internal.time.Clock;
import org.gradle.internal.time.Time;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static org.gradle.test.fixtures.server.http.CyclicBarrierRequestHandler.format;
import static org.gradle.test.fixtures.server.http.CyclicBarrierRequestHandler.selectPending;

class CyclicBarrierAnyOfRequestHandler implements TrackingHttpHandler, WaitPrecondition, BlockingHttpServer.BlockingHandler {
    private final Lock lock;
    private final Condition condition;
    private final List<String> received = new ArrayList<String>();
    private final List<String> released = new ArrayList<String>();
    private final List<ResourceHandlerWrapper> notReleased = new ArrayList<ResourceHandlerWrapper>();
    private final List<ResourceHandlerWrapper> notReceived = new ArrayList<ResourceHandlerWrapper>();
    private final int testId;
    private final int timeoutMs;
    private final Clock clock = Time.clock();
    private int waitingFor;
    private final WaitPrecondition previous;
    private long mostRecentEvent;
    protected AssertionError failure;

    CyclicBarrierAnyOfRequestHandler(Lock lock, int testId, int timeoutMs, int maxConcurrent, WaitPrecondition previous, Collection<? extends ResourceExpectation> expectedRequests) {
        if (expectedRequests.size() < maxConcurrent) {
            throw new IllegalArgumentException("Too few requests specified.");
        }
        this.lock = lock;
        this.condition = lock.newCondition();
        this.testId = testId;
        this.timeoutMs = timeoutMs;
        this.waitingFor = maxConcurrent;
        this.previous = previous;
        for (ResourceExpectation expectation : expectedRequests) {
            ResourceHandlerWrapper handler = new ResourceHandlerWrapper(lock, expectation);
            notReceived.add(handler);
        }
    }

    @Override
    public WaitPrecondition getWaitPrecondition() {
        return this;
    }

    @Override
    public void assertCanWait() throws IllegalStateException {
        lock.lock();
        try {
            if (notReceived.isEmpty() || !released.isEmpty()) {
                // Have received all requests, or have released something, so downstream can wait. This isn't quite right
                return;
            }
            previous.assertCanWait();
            throw new IllegalStateException(String.format("Cannot wait as no requests have been released. Waiting for %s, received %s.", format(notReceived), received));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ResourceHandler handle(int id, HttpExchange httpExchange) throws Exception {
        ResourceHandlerWrapper handler;
        lock.lock();
        try {
            if (notReceived.isEmpty()) {
                // barrier open, let it travel on
                return null;
            }
            if (failure != null) {
                // Busted
                throw failure;
            }

            long now = clock.getCurrentTime();
            if (mostRecentEvent < now) {
                mostRecentEvent = now;
            }

            String path = httpExchange.getRequestURI().getPath().substring(1);
            handler = selectPending(notReceived, path);
            if (handler == null || !handler.getMethod().equals(httpExchange.getRequestMethod()) || waitingFor == 0) {
                failure = new AssertionError(String.format("Unexpected request %s %s received. Waiting for %s further requests, already received %s, released %s, still expecting %s.", httpExchange.getRequestMethod(), path, waitingFor, received, released, format(notReceived)));
                condition.signalAll();
                throw failure;
            }

            notReceived.remove(handler);
            notReleased.add(handler);
            received.add(httpExchange.getRequestMethod() + " " + path);
            handler.received();
            waitingFor--;
            if (waitingFor == 0) {
                condition.signalAll();
            }

            while (!handler.isReleased() && failure == null) {
                long waitMs = mostRecentEvent + timeoutMs - clock.getCurrentTime();
                if (waitMs < 0) {
                    if (waitingFor > 0) {
                        System.out.println(String.format("[%d] timeout waiting for other requests", id));
                        throw timeoutWaitingForRequests();
                    }
                    System.out.println(String.format("[%d] timeout waiting to be released", id));
                    failure = new AssertionError(String.format("Timeout waiting to be released. Waiting for %s further requests, received %s, released %s, not yet received %s.", waitingFor, received, released, format(notReceived)));
                    condition.signalAll();
                    throw failure;
                }
                System.out.println(String.format("[%d] waiting to be released. Still waiting for %s further requests, already received %s", id, waitingFor, received));
                condition.await(waitMs, TimeUnit.MILLISECONDS);
            }
            if (failure != null) {
                // Broken in another thread
                System.out.println(String.format("[%d] failure in another thread", id));
                throw failure;
            }
        } finally {
            lock.unlock();
        }

        return handler;
    }

    public void assertComplete() {
        lock.lock();
        try {
            if (failure != null) {
                throw failure;
            }
            if (!notReceived.isEmpty()) {
                throw new AssertionError(String.format("Did not handle all expected requests. Waiting for %d further requests, received %s, released %s, not yet received %s.", waitingFor, received, released, format(notReceived)));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(String path) {
        path = BlockingHttpServer.normalizePath(path);
        lock.lock();
        try {
            ResourceHandlerWrapper handler = selectPending(notReleased, path);
            if (handler == null) {
                throw new IllegalStateException("Expected request already released.");
            }
            if (!handler.isReceived()) {
                throw new IllegalStateException("Expected request not received, should wait for pending calls first.");
            }
            System.out.println(String.format("[%d] releasing %s", testId, path));
            released.add(handler.getMethod() + " " + path);
            handler.released();
            notReleased.remove(handler);
            doRelease(1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseAll() {
        lock.lock();
        try {
            if (!notReceived.isEmpty()) {
                throw new IllegalStateException("Expected requests not received, should wait for pending calls first.");
            }
            doReleaseAll();
        } finally {
            lock.unlock();
        }
    }

    protected void doReleaseAll() {
        lock.lock();
        try {
            int count = 0;
            for (ResourceHandlerWrapper resourceHandler : notReleased) {
                System.out.println(String.format("[%d] releasing %s", testId, resourceHandler.getPath()));
                released.add(resourceHandler.getMethod() + " " + resourceHandler.getPath());
                resourceHandler.released();
                count++;
            }
            notReleased.clear();
            doRelease(count);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(int count) {
        lock.lock();
        try {
            if (notReleased.size() < count) {
                throw new IllegalStateException("Too few requests released, should wait for pending calls first.");
            }
            for (int i = 0; i < count; i++) {
                ResourceHandlerWrapper resourceHandler = notReleased.remove(0);
                System.out.println(String.format("[%d] releasing %s", testId, resourceHandler.getPath()));
                released.add(resourceHandler.getMethod() + " " + resourceHandler.getPath());
                resourceHandler.released();
            }
            doRelease(count);
        } finally {
            lock.unlock();
        }
    }

    private void doRelease(int count) {
        waitingFor = Math.min(notReceived.size(), waitingFor + count);
        System.out.println(String.format("[%d] now expecting %d further requests, received %s, released %s, not yet received %s", testId, waitingFor, received, released, format(notReceived)));
        condition.signalAll();
    }

    @Override
    public void waitForAllPendingCalls() {
        lock.lock();
        try {
            previous.assertCanWait();

            long now = clock.getCurrentTime();
            if (mostRecentEvent < now) {
                mostRecentEvent = now;
            }

            while (waitingFor > 0 && failure == null) {
                long waitMs = mostRecentEvent + timeoutMs - clock.getCurrentTime();
                if (waitMs < 0) {
                    System.out.println(String.format("[%d] timeout waiting for expected requests.", testId));
                    throw timeoutWaitingForRequests();
                }
                System.out.println(String.format("[%d] waiting for %d further requests, received %s, released %s, not yet received %s", testId, waitingFor, received, released, format(notReceived)));
                try {
                    condition.await(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            System.out.println(String.format("[%d] expected requests received, received %s, released %s, not yet received %s", testId, received, released, format(notReceived)));
        } finally {
            lock.unlock();
        }
    }

    private AssertionError timeoutWaitingForRequests() {
        failure = new AssertionError(String.format("Timeout waiting for expected requests. Waiting for %d further requests, received %s, released %s, not yet received %s.", waitingFor, received, released, format(notReceived)));
        condition.signalAll();
        throw failure;
    }
}
