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
package org.apache.beam.runners.flink.translation;

import org.apache.beam.runners.flink.translation.types.CoderTypeInformation;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.AppliedPTransform;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;

import com.google.common.base.Preconditions;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for keeping track of which {@link DataStream DataStreams} map
 * to which {@link PTransform PTransforms}.
 */
public class FlinkStreamingTranslationContext {

  private final StreamExecutionEnvironment env;
  private final PipelineOptions options;

  /**
   * Keeps a mapping between the output value of the PTransform (in Dataflow) and the
   * Flink Operator that produced it, after the translation of the correspondinf PTransform
   * to its Flink equivalent.
   * */
  private final Map<PValue, DataStream<?>> dataStreams;

  private AppliedPTransform<?, ?, ?> currentTransform;

  public FlinkStreamingTranslationContext(StreamExecutionEnvironment env, PipelineOptions options) {
    this.env = Preconditions.checkNotNull(env);
    this.options = Preconditions.checkNotNull(options);
    this.dataStreams = new HashMap<>();
  }

  public StreamExecutionEnvironment getExecutionEnvironment() {
    return env;
  }

  public PipelineOptions getPipelineOptions() {
    return options;
  }

  @SuppressWarnings("unchecked")
  public <T> DataStream<T> getInputDataStream(PValue value) {
    return (DataStream<T>) dataStreams.get(value);
  }

  public void setOutputDataStream(PValue value, DataStream<?> set) {
    if (!dataStreams.containsKey(value)) {
      dataStreams.put(value, set);
    }
  }

  /**
   * Sets the AppliedPTransform which carries input/output.
   * @param currentTransform
   */
  public void setCurrentTransform(AppliedPTransform<?, ?, ?> currentTransform) {
    this.currentTransform = currentTransform;
  }

  @SuppressWarnings("unchecked")
  public <T> TypeInformation<WindowedValue<T>> getTypeInfo(PCollection<T> collection) {
    Coder<T> valueCoder = collection.getCoder();
    WindowedValue.FullWindowedValueCoder<T> windowedValueCoder =
        WindowedValue.getFullCoder(
            valueCoder,
            collection.getWindowingStrategy().getWindowFn().windowCoder());

    return new CoderTypeInformation<>(windowedValueCoder);
  }


  @SuppressWarnings("unchecked")
  public <T extends PInput> T getInput(PTransform<T, ?> transform) {
    return (T) currentTransform.getInput();
  }

  @SuppressWarnings("unchecked")
  public <T extends POutput> T getOutput(PTransform<?, T> transform) {
    return (T) currentTransform.getOutput();
  }
}
