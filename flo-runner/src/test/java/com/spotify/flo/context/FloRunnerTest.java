/*-
 * -\-\-
 * Flo Runner
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
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
 * -/-/-
 */

package com.spotify.flo.context;

import static com.spotify.flo.context.FloRunner.runTask;
import static com.spotify.flo.context.NotDoneException.NOT_DONE;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.Uninterruptibles;
import com.spotify.flo.Task;
import com.spotify.flo.TaskBuilder.F0;
import com.spotify.flo.TaskId;
import com.spotify.flo.Tracing;
import com.spotify.flo.context.FloRunner.Result;
import com.spotify.flo.freezer.Persisted;
import com.spotify.flo.status.NotReady;
import io.grpc.Context;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;

public class FloRunnerTest {

  final Task<String> FOO_TASK = Task.named("task").ofType(String.class)
      .process(() -> "foo");

  private TerminationHook validTerminationHook;
  private TerminationHook exceptionalTerminationHook;

  @Before
  public void setUp() {
    exceptionalTerminationHook = mock(TerminationHook.class);
    doThrow(new RuntimeException("hook exception")).when(exceptionalTerminationHook).accept(any());

    validTerminationHook = mock(TerminationHook.class);
    doNothing().when(validTerminationHook).accept(any());

    TestTerminationHookFactory.injectCreator((config) -> validTerminationHook);
  }

  @Test
  public void nonBlockingRunnerDoesNotBlock() {
    final AtomicBoolean hasHappened = new AtomicBoolean(false);
    final CountDownLatch latch = new CountDownLatch(1);
    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            latch.await();
            hasHappened.set(true);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          return null;
        });

    runTask(task);

    assertThat(hasHappened.get(), is(false));

    latch.countDown();
  }

  @Test
  public void blockingRunnerBlocks() {
    final AtomicBoolean hasHappened = new AtomicBoolean();
    final Task<Void> task = Task.named("task").ofType(Void.class)
        .process(() -> {
          try {
            Thread.sleep(10);
            hasHappened.set(true);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          return null;
        });

    runTask(task).waitAndExit(status -> { });

    assertThat(hasHappened.get(), is(true));
  }

  @Test
  public void valueIsPassedInFuture() throws Exception {
    final String result = runTask(FOO_TASK).future().get(1, TimeUnit.SECONDS);

    assertThat(result, is("foo"));
  }

  @Test
  public void exceptionsArePassed() throws Exception {
    final RuntimeException expectedException = new RuntimeException("foo");

    final Task<String> task = Task.named("foo").ofType(String.class)
        .process(() -> {
          throw expectedException;
        });

    Throwable exception = null;
    try {
      runTask(task).value();
    } catch (ExecutionException e) {
      exception = e.getCause();
    }
    assertThat(exception, is(expectedException));
  }

  @Test
  public void persistedExitsZero() {
    final Task<Void> task = Task.named("persisted").ofType(Void.class)
        .process(() -> {
          throw new Persisted();
        });

    AtomicInteger status = new AtomicInteger(1);

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(0));
  }

  @Test
  public void valuesCanBeWaitedOn() throws Exception {
    final String result = runTask(FOO_TASK).value();

    assertThat(result, is("foo"));
  }

  @Test
  public void notReadyExitsTwenty() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new NotReady();
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(20));
  }

  @Test
  public void exceptionsExitNonZero() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    AtomicInteger status = new AtomicInteger();

    runTask(task).waitAndExit(status::set);

    assertThat(status.get(), is(1));
  }

  @Test
  public void ignoreExceptionsFromTerminationHook() {
    TestTerminationHookFactory.injectHook(exceptionalTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(exceptionalTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskSuccess() {
    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(FOO_TASK).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(0));
    assertThat(status.get(), is(0));
  }

  @Test
  public void validateTerminationHookInvocationOnTaskFailure() {
    final Task<String> task = Task.named("task").ofType(String.class)
        .process(() -> {
          throw new RuntimeException("this task should throw");
        });

    TestTerminationHookFactory.injectHook(validTerminationHook);

    AtomicInteger status = new AtomicInteger();
    runTask(task).waitAndExit(status::set);

    verify(validTerminationHook, times(1)).accept(eq(1));
    assertThat(status.get(), is(1));
  }

  @Test(expected = RuntimeException.class)
  public void failOnExceptionalTerminationHookFactory() {
    TestTerminationHookFactory.injectCreator((config) -> {
      throw new RuntimeException("factory exception");
    });
    runTask(FOO_TASK);
  }

  @Test
  public void contextIsPropagated() throws Exception {
    final Context.Key<String> fooKey = Context.key("foo");
    final String fooValue = "foobar";

    final Task<String> task = Task.named("task").ofType(String.class)
        .process(fooKey::get);

    final Result<String> result = Context.current().withValue(fooKey, fooValue)
        .call(() -> runTask(task));

    assertThat(result.value(), is(fooValue));
  }

  @Test
  public void taskIdIsInContext() throws Exception {
    final Task<TaskId> task = Task.named("task").ofType(TaskId.class)
        .process(Tracing.TASK_ID::get);

    final Result<TaskId> result = runTask(task);

    assertThat(result.value(), is(task.id()));
  }


  @Test
  public void testPolling() throws ExecutionException, InterruptedException {
    final Task<String> task = Task.named("foobar").ofType(String.class)
        .process(new F0<String>() {
          private String jobId = null;

          @Override
          public String get() {
            System.err.println("get(): jobId=" + jobId);

            if (jobId == null) {
              jobId = startJob(() -> {
                Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
                return "foobar!";
              });
              throw NOT_DONE;
            }

            final Boolean done = isJobDone(jobId);
            if (done == null) {
              throw new RuntimeException("job lost!");
            }

            if (!done) {
              throw NOT_DONE;
            }

            final String result = jobResult(jobId);

            System.err.println("result: " + result);

            return result;
          }
        });

    final Result<String> result = FloRunner.runTask(task);

    final String value = result.value();
    System.err.println("value=" + value);
  }

  @SuppressWarnings("unchecked")
  private <T> T jobResult(String jobId) {
    final CompletableFuture<?> f = jobs.get(jobId);
    if (!f.isDone()) {
      throw new IllegalStateException();
    }
    return (T) f.getNow(null);
  }

  private Boolean isJobDone(String jobId) {
    return Optional.ofNullable(jobs.get(jobId)).map(CompletableFuture::isDone).orElse(null);
  }

  private ConcurrentMap<String, CompletableFuture<?>> jobs = new ConcurrentHashMap<>();

  private <T> String startJob(Supplier<T> job) {
    final String id = UUID.randomUUID().toString();
    jobs.putIfAbsent(id, CompletableFuture.supplyAsync(job));
    return id;
  }
}
