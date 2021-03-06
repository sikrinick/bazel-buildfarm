// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.worker;

import static build.bazel.remote.execution.v2.ExecutionStage.Value.QUEUED;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.buildfarm.common.Poller;
import build.buildfarm.instance.Instance.MatchListener;
import build.buildfarm.v1test.ExecuteEntry;
import build.buildfarm.v1test.QueueEntry;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class MatchStage extends PipelineStage {
  private static final Logger logger = Logger.getLogger(MatchStage.class.getName());

  public MatchStage(WorkerContext workerContext, PipelineStage output, PipelineStage error) {
    super("MatchStage", workerContext, output, error);
  }

  class MatchOperationListener implements MatchListener {
    private Stopwatch stopwatch;
    private long waitStart;
    private long waitDuration;
    private long operationNamedAtUSecs;
    private Poller poller = null;
    private QueueEntry queueEntry = null;
    private boolean matched = false;
    private Runnable onCancelHandler = null; // never called, only blocking stub used

    public MatchOperationListener(Stopwatch stopwatch) {
      this.stopwatch = stopwatch;
      waitDuration = this.stopwatch.elapsed(MICROSECONDS);
    }

    boolean wasMatched() {
      return matched;
    }

    @Override
    public void onWaitStart() {
      waitStart = stopwatch.elapsed(MICROSECONDS);
    }

    @Override
    public void onWaitEnd() {
      long elapsedUSecs = stopwatch.elapsed(MICROSECONDS);
      waitDuration += elapsedUSecs - waitStart;
      waitStart = elapsedUSecs;
    }

    @Override
    public boolean onEntry(@Nullable QueueEntry queueEntry) throws InterruptedException {
      if (queueEntry == null) {
        return false;
      }

      operationNamedAtUSecs = stopwatch.elapsed(MICROSECONDS);
      Preconditions.checkState(poller == null);
      Poller poller = workerContext.createPoller("MatchStage", queueEntry, QUEUED);
      return onOperationPolled(queueEntry, poller);
    }

    @Override
    public void onError(Throwable t) {
      Throwables.throwIfUnchecked(t);
      throw new RuntimeException(t);
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
      this.onCancelHandler = onCancelHandler;
    }

    private boolean onOperationPolled(QueueEntry queueEntry, Poller poller) throws InterruptedException {
      String operationName = queueEntry.getExecuteEntry().getOperationName();
      logStart(operationName);

      long matchingAtUSecs = stopwatch.elapsed(MICROSECONDS);
      OperationContext operationContext = match(
          queueEntry,
          poller,
          stopwatch,
          operationNamedAtUSecs);
      long matchedInUSecs = stopwatch.elapsed(MICROSECONDS) - matchingAtUSecs;
      logComplete(operationName, matchedInUSecs, waitDuration, true);
      operationContext.poller.pause();
      try {
        output.put(operationContext);
      } catch (InterruptedException e) {
        error.put(operationContext);
        throw e;
      }
      matched = true;
      return true;
    }
  }

  @Override
  protected Logger getLogger() {
    return logger;
  }

  @Override
  protected void iterate() throws InterruptedException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    if (!output.claim()) {
      return;
    }
    MatchOperationListener listener = new MatchOperationListener(stopwatch);
    try {
      logStart();
      workerContext.match(listener);
    } finally {
      if (!listener.wasMatched()) {
        output.release();
      }
    }
  }

  private OperationContext match(
      QueueEntry queueEntry,
      Poller poller,
      Stopwatch stopwatch,
      long matchStartAtUSecs) {
    OperationContext.Builder builder = OperationContext.newBuilder();
    Timestamp workerStartTimestamp = Timestamps.fromMillis(System.currentTimeMillis());

    ExecuteEntry executeEntry = queueEntry.getExecuteEntry();
    // this may be superfluous - we can probably just set the name and action digest
    Operation operation = Operation.newBuilder()
        .setName(executeEntry.getOperationName())
        .setMetadata(Any.pack(ExecuteOperationMetadata.newBuilder()
            .setActionDigest(executeEntry.getActionDigest())
            .setStage(QUEUED)
            .setStdoutStreamName(executeEntry.getStdoutStreamName())
            .setStderrStreamName(executeEntry.getStderrStreamName())
            .build()))
        .build();

    OperationContext operationContext = OperationContext.newBuilder()
        .setOperation(operation)
        .setPoller(poller)
        .setQueueEntry(queueEntry)
        .build();

    operationContext.executeResponse.getResultBuilder().getExecutionMetadataBuilder()
        .setWorker(workerContext.getName())
        .setQueuedTimestamp(executeEntry.getQueuedTimestamp())
        .setWorkerStartTimestamp(workerStartTimestamp);
    return operationContext;
  }

  @Override
  public OperationContext take() throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean claim() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void release() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void put(OperationContext operation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setInput(PipelineStage input) {
    throw new UnsupportedOperationException();
  }
}
