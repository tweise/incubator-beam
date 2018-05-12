/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink;

import com.google.common.collect.Iterables;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.runners.core.SystemReduceFn;
import org.apache.beam.runners.core.construction.PTransformTranslation;
import org.apache.beam.runners.core.construction.PipelineOptionsTranslation;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.runners.core.construction.WindowingStrategyTranslation;
import org.apache.beam.runners.core.construction.graph.ExecutableStage;
import org.apache.beam.runners.core.construction.graph.PipelineNode;
import org.apache.beam.runners.core.construction.graph.QueryablePipeline;
import org.apache.beam.runners.flink.translation.functions.FlinkAssignWindows;
import org.apache.beam.runners.flink.translation.types.CoderTypeInformation;
import org.apache.beam.runners.flink.translation.wrappers.streaming.DoFnOperator;
import org.apache.beam.runners.flink.translation.wrappers.streaming.SingletonKeyedWorkItem;
import org.apache.beam.runners.flink.translation.wrappers.streaming.SingletonKeyedWorkItemCoder;
import org.apache.beam.runners.flink.translation.wrappers.streaming.WindowDoFnOperator;
import org.apache.beam.runners.flink.translation.wrappers.streaming.WorkItemKeySelector;
import org.apache.beam.runners.fnexecution.provisioning.JobInfo;
import org.apache.beam.runners.fnexecution.wire.WireCoders;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.IterableCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowedValue.WindowedValueCoder;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.util.Collector;

/**
 * Translate an unbounded portable pipeline representation into a Flink pipeline representation.
 */
public class FlinkStreamingPortablePipelineTranslator implements FlinkPortablePipelineTranslator<
        FlinkStreamingPortablePipelineTranslator.StreamingTranslationContext> {

  /**
   * Creates a streaming translation context. The resulting Flink execution dag will live in a new
   * {@link StreamExecutionEnvironment}.
   */
  public static StreamingTranslationContext createTranslationContext(JobInfo jobInfo) {
    PipelineOptions pipelineOptions;
    try {
      pipelineOptions = PipelineOptionsTranslation.fromProto(jobInfo.pipelineOptions());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    StreamExecutionEnvironment executionEnvironment =
            FlinkExecutionEnvironments.createStreamExecutionEnvironment(
                    pipelineOptions.as(FlinkPipelineOptions.class));
    return new StreamingTranslationContext(jobInfo, pipelineOptions, executionEnvironment);
  }

  /**
   * Streaming translation context. Stores metadata about known PCollections/DataStreams and holds
   * the Flink {@link StreamExecutionEnvironment} that the execution plan will be applied to.
   */

  public static class StreamingTranslationContext
          implements FlinkPortablePipelineTranslator.TranslationContext {

    private final JobInfo jobInfo;
    private final PipelineOptions options;
    private final StreamExecutionEnvironment executionEnvironment;
    private final Map<String, DataStream<?>> dataStreams;

    private StreamingTranslationContext(
            JobInfo jobInfo,
            PipelineOptions options,
            StreamExecutionEnvironment executionEnvironment) {
      this.jobInfo = jobInfo;
      this.options = options;
      this.executionEnvironment = executionEnvironment;
      dataStreams = new HashMap<>();

    }

    @Override
    public JobInfo getJobInfo() {
      return jobInfo;
    }

    public PipelineOptions getPipelineOptions() {
      return options;
    }

    public StreamExecutionEnvironment getExecutionEnvironment() {
      return executionEnvironment;
    }

    public <T> void addDataStream(String pCollectionId, DataStream<T> dataSet) {
      dataStreams.put(pCollectionId, dataSet);
    }

    public <T> DataStream<T> getDataStreamOrThrow(String pCollectionId) {
      DataStream<T> dataSet = (DataStream<T>) dataStreams.get(pCollectionId);
      if (dataSet == null) {
        throw new IllegalArgumentException(
                String.format("Unknown datastream for id %s.", pCollectionId));
      }
      return dataSet;
    }

  }

  interface PTransformTranslator<T> {
    void translate(String id, RunnerApi.Pipeline pipeline, T t);
  }

  private final Map<String, PTransformTranslator<StreamingTranslationContext>>
          urnToTransformTranslator = new HashMap<>();

  FlinkStreamingPortablePipelineTranslator() {
    urnToTransformTranslator.put(PTransformTranslation.FLATTEN_TRANSFORM_URN,
            this::translateFlatten);
    urnToTransformTranslator.put(PTransformTranslation.GROUP_BY_KEY_TRANSFORM_URN,
            this::translateGroupByKey);
    urnToTransformTranslator.put(PTransformTranslation.IMPULSE_TRANSFORM_URN,
            this::translateImpulse);
    urnToTransformTranslator.put(PTransformTranslation.ASSIGN_WINDOWS_TRANSFORM_URN,
            this::translateAssignWindows);
    urnToTransformTranslator.put(ExecutableStage.URN,
        this::translateExecutableStage);
    urnToTransformTranslator.put(PTransformTranslation.RESHUFFLE_URN,
        this::translateReshuffle);
  }


  @Override
  public void translate(StreamingTranslationContext context, RunnerApi.Pipeline pipeline) {
    QueryablePipeline p = QueryablePipeline.forTransforms(
        pipeline.getRootTransformIdsList(), pipeline.getComponents());
    for (PipelineNode.PTransformNode transform : p.getTopologicallyOrderedTransforms()) {
      urnToTransformTranslator.getOrDefault(
              transform.getTransform().getSpec().getUrn(), this::urnNotFound)
              .translate(transform.getId(), pipeline, context);
    }

  }

  private void urnNotFound(
          String id, RunnerApi.Pipeline pipeline,
          FlinkStreamingPortablePipelineTranslator.TranslationContext context) {
    throw new IllegalArgumentException(
            String.format("Unknown type of URN %s for PTrasnform with id %s.",
                    pipeline.getComponents().getTransformsOrThrow(id).getSpec().getUrn(),
                    id));
  }

  private <K, V> void translateReshuffle(
      String id,
      RunnerApi.Pipeline pipeline,
      StreamingTranslationContext context) {
    RunnerApi.PTransform transform = pipeline.getComponents().getTransformsOrThrow(id);
    DataStream<WindowedValue<KV<K, V>>> inputDataSet =
        context.getDataStreamOrThrow(
            Iterables.getOnlyElement(transform.getInputsMap().values()));
    context.addDataStream(Iterables.getOnlyElement(transform.getOutputsMap().values()),
        inputDataSet.rebalance());
  }

  private <T>  void translateFlatten(
          String id,
          RunnerApi.Pipeline pipeline,
          StreamingTranslationContext context) {
    Map<String, String> allInputs =
            pipeline.getComponents().getTransformsOrThrow(id).getInputsMap();

    if (allInputs.isEmpty()) {

      // create an empty dummy source to satisfy downstream operations
      // we cannot create an empty source in Flink, therefore we have to
      // add the flatMap that simply never forwards the single element
      DataStreamSource<String> dummySource =
              context.getExecutionEnvironment().fromElements("dummy");

      DataStream<WindowedValue<T>> result =
              dummySource
                      .<WindowedValue<T>>flatMap(
                              (s, collector) -> {
                                // never return anything
                              })
                      .returns(
                              new CoderTypeInformation<>(
                                      WindowedValue.getFullCoder(
                                              (Coder<T>) VoidCoder.of(),
                                              GlobalWindow.Coder.INSTANCE)));
      context.addDataStream(Iterables.getOnlyElement(
              pipeline.getComponents().getTransformsOrThrow(id).getOutputsMap().values()), result);
    } else {
      DataStream<T> result = null;

      // Determine DataStreams that we use as input several times. For those, we need to uniquify
      // input streams because Flink seems to swallow watermarks when we have a union of one and
      // the same stream.
      Map<DataStream<T>, Integer> duplicates = new HashMap<>();
      for (String input : allInputs.values()) {
        DataStream<T> current = context.getDataStreamOrThrow(input);
        Integer oldValue = duplicates.put(current, 1);
        if (oldValue != null) {
          duplicates.put(current, oldValue + 1);
        }
      }

      for (String input : allInputs.values()) {
        DataStream<T> current = context.getDataStreamOrThrow(input);

        final Integer timesRequired = duplicates.get(current);
        if (timesRequired > 1) {
          current = current.flatMap(new FlatMapFunction<T, T>() {
            private static final long serialVersionUID = 1L;

            @Override
            public void flatMap(T t, Collector<T> collector) throws Exception {
              collector.collect(t);
            }
          });
        }
        result = (result == null) ? current : result.union(current);
      }

      context.addDataStream(Iterables.getOnlyElement(
              pipeline.getComponents().getTransformsOrThrow(id).getOutputsMap().values()), result);
    }
  }

  private <K, V> void translateGroupByKey(
          String id,
          RunnerApi.Pipeline pipeline,
          StreamingTranslationContext context) {

    RunnerApi.PTransform pTransform =
            pipeline.getComponents().getTransformsOrThrow(id);
    String inputPCollectionId =
            Iterables.getOnlyElement(pTransform.getInputsMap().values());
    String outputPCollectionId =
            Iterables.getOnlyElement(pTransform.getOutputsMap().values());

    RunnerApi.WindowingStrategy windowingStrategyProto =
            pipeline.getComponents().getWindowingStrategiesOrThrow(
                    pipeline.getComponents().getPcollectionsOrThrow(
                            inputPCollectionId).getWindowingStrategyId());

    DataStream<WindowedValue<KV<K, V>>> inputDataStream =
            context.getDataStreamOrThrow(inputPCollectionId);

    RehydratedComponents rehydratedComponents =
            RehydratedComponents.forComponents(pipeline.getComponents());

    WindowingStrategy<?, ?> windowingStrategy;
    try {
      windowingStrategy = WindowingStrategyTranslation.fromProto(
                      windowingStrategyProto,
                      rehydratedComponents);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(
              String.format("Unable to hydrate GroupByKey windowing strategy %s.",
                      windowingStrategyProto), e);
    }

    WindowedValueCoder<KV<K, V>> inputCoder = (WindowedValueCoder) instantiateCoder(
            inputPCollectionId, pipeline.getComponents());
    KvCoder<K, V> inputElementCoder = (KvCoder<K, V>) inputCoder.getValueCoder();

    SingletonKeyedWorkItemCoder<K, V> workItemCoder = SingletonKeyedWorkItemCoder.of(
            inputElementCoder.getKeyCoder(),
            inputElementCoder.getValueCoder(),
            windowingStrategy.getWindowFn().windowCoder());

    WindowedValue.
            FullWindowedValueCoder<SingletonKeyedWorkItem<K, V>> windowedWorkItemCoder =
            WindowedValue.getFullCoder(
                    workItemCoder,
                    windowingStrategy.getWindowFn().windowCoder());

    CoderTypeInformation<WindowedValue<SingletonKeyedWorkItem<K, V>>> workItemTypeInfo =
            new CoderTypeInformation<>(windowedWorkItemCoder);

    DataStream<WindowedValue<SingletonKeyedWorkItem<K, V>>> workItemStream =
            inputDataStream
                    .flatMap(new FlinkStreamingTransformTranslators.ToKeyedWorkItem<>())
                    .returns(workItemTypeInfo)
                    .name("ToKeyedWorkItem");

    KeyedStream<WindowedValue<SingletonKeyedWorkItem<K, V>>, ByteBuffer>
            keyedWorkItemStream =
            workItemStream.keyBy(new WorkItemKeySelector<>(inputElementCoder.getKeyCoder()));

    SystemReduceFn<K, V, Iterable<V>, Iterable<V>, BoundedWindow> reduceFn =
            SystemReduceFn.buffering(inputElementCoder.getValueCoder());

    Coder<Iterable<V>> accumulatorCoder = IterableCoder.of(inputElementCoder.getValueCoder());

    Coder<WindowedValue<KV<K, Iterable<V>>>> outputCoder = WindowedValue.getFullCoder(
            KvCoder.of(inputElementCoder.getKeyCoder(), accumulatorCoder),
                    windowingStrategy.getWindowFn().windowCoder());

    TypeInformation<WindowedValue<KV<K, Iterable<V>>>> outputTypeInfo =
            new CoderTypeInformation<>(outputCoder);

    TupleTag<KV<K, Iterable<V>>> mainTag = new TupleTag<>("main output");

    // TODO: remove non-portable operator re-use
    WindowDoFnOperator<K, V, Iterable<V>> doFnOperator =
            new WindowDoFnOperator<K, V, Iterable<V>>(
                    reduceFn,
                    pTransform.getUniqueName(),
                    (Coder) windowedWorkItemCoder,
                    mainTag,
                    Collections.emptyList(),
                    new DoFnOperator.MultiOutputOutputManagerFactory(mainTag, outputCoder),
                    windowingStrategy,
                    new HashMap<>(), /* side-input mapping */
                    Collections.emptyList(), /* side inputs */
                    context.getPipelineOptions(),
                    inputElementCoder.getKeyCoder());

    SingleOutputStreamOperator<WindowedValue<KV<K, Iterable<V>>>> outputDataStream =
            keyedWorkItemStream
                    .transform(
                            pTransform.getUniqueName(),
                            outputTypeInfo,
                            (OneInputStreamOperator) doFnOperator);

    context.addDataStream(
            Iterables.getOnlyElement(pTransform.getOutputsMap().values()),
            outputDataStream);

  }

  private void translateImpulse(
          String id,
          RunnerApi.Pipeline pipeline,
          StreamingTranslationContext context) {
    RunnerApi.PTransform pTransform =
            pipeline.getComponents().getTransformsOrThrow(id);

    TypeInformation<WindowedValue<byte[]>> typeInfo =
            new CoderTypeInformation<>(
                    WindowedValue.getFullCoder(ByteArrayCoder.of(), GlobalWindow.Coder.INSTANCE));

    DataStreamSource<WindowedValue<byte[]>> source = context.getExecutionEnvironment()
            .fromCollection(Collections.singleton(WindowedValue.valueInGlobalWindow(
                    new byte[0])), typeInfo);

    context.addDataStream(
            Iterables.getOnlyElement(pTransform.getOutputsMap().values()),
            source);
  }

  private <T> void translateAssignWindows(String id, RunnerApi.Pipeline pipeline,
                                          StreamingTranslationContext context) {
    RunnerApi.Components components = pipeline.getComponents();
    RunnerApi.PTransform transform = components.getTransformsOrThrow(id);
    RunnerApi.WindowIntoPayload payload;
    try {
      payload = RunnerApi.WindowIntoPayload.parseFrom(transform.getSpec().getPayload());
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalArgumentException(e);
    }
    WindowFn<T, ? extends BoundedWindow> windowFn = (WindowFn<T, ? extends BoundedWindow>)
            WindowingStrategyTranslation.windowFnFromProto(payload.getWindowFn());

    String inputCollectionId = Iterables.getOnlyElement(transform.getInputsMap().values());
    String outputCollectionId =
            Iterables.getOnlyElement(transform.getOutputsMap().values());
    Coder<WindowedValue<T>> outputCoder = instantiateCoder(outputCollectionId, components);
    TypeInformation<WindowedValue<T>> resultTypeInfo =
            new CoderTypeInformation<>(outputCoder);

    DataStream<WindowedValue<T>> inputDataStream = context.getDataStreamOrThrow(inputCollectionId);

    FlinkAssignWindows<T, ? extends BoundedWindow> assignWindowsFunction =
            new FlinkAssignWindows<>(windowFn);

    DataStream<WindowedValue<T>> resultDataStream = inputDataStream
            .flatMap(assignWindowsFunction)
            .name(transform.getUniqueName())
            .returns(resultTypeInfo);

    context.addDataStream(outputCollectionId, resultDataStream);
  }

  private <InputT, OutputT> void translateExecutableStage(
          String id,
          RunnerApi.Pipeline pipeline,
          StreamingTranslationContext context) {

    throw new RuntimeException("executable stage translation not implemented");

  }

  static <T> Coder<WindowedValue<T>> instantiateCoder(String collectionId,
                                                      RunnerApi.Components components) {
    PipelineNode.PCollectionNode collectionNode =
            PipelineNode.pCollection(
                    collectionId, components.getPcollectionsOrThrow(collectionId));
    try {
      return WireCoders.instantiateRunnerWireCoder(collectionNode, components);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
