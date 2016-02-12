/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util.state;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.annotations.Experimental.Kind;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.Combine.KeyedCombineFn;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.OutputTimeFn;
import com.google.cloud.dataflow.sdk.util.state.StateTag.StateBinder;

import org.joda.time.Instant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * In-memory implementation of {@link StateInternals}. Used in {@code BatchModeExecutionContext}
 * and for running tests that need state.
 */
@Experimental(Kind.STATE)
public class InMemoryStateInternals<K> implements StateInternals<K> {

  public static <K> InMemoryStateInternals<K> forKey(K key) {
    return new InMemoryStateInternals<>(key);
  }

  private final K key;

  protected InMemoryStateInternals(K key) {
    this.key = key;
  }

  @Override
  public K getKey() {
    return key;
  }

  private interface InMemoryState {
    boolean isEmptyForTesting();
  }

  protected final StateTable<K> inMemoryState = new StateTable<K>() {
    @Override
    protected StateBinder<K> binderForNamespace(final StateNamespace namespace) {
      return new StateBinder<K>() {
        @Override
        public <T> ValueState<T> bindValue(
            StateTag<? super K, ValueState<T>> address, Coder<T> coder) {
          return new InMemoryValue<T>();
        }

        @Override
        public <T> BagState<T> bindBag(
            final StateTag<? super K, BagState<T>> address, Coder<T> elemCoder) {
          return new InMemoryBag<T>();
        }

        @Override
        public <InputT, AccumT, OutputT> CombiningValueStateInternal<InputT, AccumT, OutputT>
            bindCombiningValue(
                StateTag<? super K, CombiningValueStateInternal<InputT, AccumT, OutputT>>
                address, Coder<AccumT> accumCoder,
                final CombineFn<InputT, AccumT, OutputT> combineFn) {
          return new InMemoryCombiningValue<InputT, AccumT, OutputT>(combineFn.<K>asKeyedFn());
        }

        @Override
        public <W extends BoundedWindow> WatermarkStateInternal<W> bindWatermark(
            StateTag<? super K, WatermarkStateInternal<W>> address,
            OutputTimeFn<? super W> outputTimeFn) {
          return new WatermarkStateInternalImplementation<W>(outputTimeFn);
        }

        @Override
        public <InputT, AccumT, OutputT> CombiningValueStateInternal<InputT, AccumT, OutputT>
            bindKeyedCombiningValue(
                StateTag<? super K, CombiningValueStateInternal<InputT, AccumT, OutputT>>
                address, Coder<AccumT> accumCoder,
                KeyedCombineFn<? super K, InputT, AccumT, OutputT> combineFn) {
          return new InMemoryCombiningValue<InputT, AccumT, OutputT>(combineFn);
        }
      };
    }
  };

  public void clear() {
    inMemoryState.clear();
  }

  /**
   * Return true if the given state is empty. This is used by the test framework to make sure
   * that the state has been properly cleaned up.
   */
  protected boolean isEmptyForTesting(State state) {
    return ((InMemoryState) state).isEmptyForTesting();
  }

  @Override
  public <T extends State> T state(StateNamespace namespace, StateTag<? super K, T> address) {
    return inMemoryState.get(namespace, address);
  }

  private final class InMemoryValue<T> implements ValueState<T>, InMemoryState {
    private boolean isCleared = true;
    private T value = null;

    @Override
    public void clear() {
      // Even though we're clearing we can't remove this from the in-memory state map, since
      // other users may already have a handle on this Value.
      value = null;
      isCleared = true;
    }

    @Override
    public StateContents<T> get() {
      return new StateContents<T>() {
        @Override
        public T read() {
          return value;
        }
      };
    }

    @Override
    public void set(T input) {
      isCleared = false;
      this.value = input;
    }

    @Override
    public boolean isEmptyForTesting() {
      return isCleared;
    }
  }

  private final class WatermarkStateInternalImplementation<W extends BoundedWindow>
      implements WatermarkStateInternal<W>, InMemoryState {

    private final OutputTimeFn<? super W> outputTimeFn;

    @Nullable
    private Instant combinedHold = null;

    public WatermarkStateInternalImplementation(OutputTimeFn<? super W> outputTimeFn) {
      this.outputTimeFn = outputTimeFn;
    }

    @Override
    public void clear() {
      // Even though we're clearing we can't remove this from the in-memory state map, since
      // other users may already have a handle on this WatermarkBagInternal.
      combinedHold = null;
    }

    @Override
    public StateContents<Instant> get() {
      return new StateContents<Instant>() {
        @Override
        public Instant read() {
          return combinedHold;
        }
      };
    }

    @Override
    public void add(Instant outputTime) {
      combinedHold = combinedHold == null ? outputTime
          : outputTimeFn.combine(combinedHold, outputTime);
    }

    @Override
    public boolean isEmptyForTesting() {
      return combinedHold == null;
    }

    @Override
    public StateContents<Boolean> isEmpty() {
      return new StateContents<Boolean>() {
        @Override
        public Boolean read() {
          return combinedHold == null;
        }
      };
    }

    @Override
    public OutputTimeFn<? super W> getOutputTimeFn() {
      return outputTimeFn;
    }

    @Override
    public String toString() {
      return Objects.toString(combinedHold);
    }
  }

  private final class InMemoryCombiningValue<InputT, AccumT, OutputT>
      implements CombiningValueStateInternal<InputT, AccumT, OutputT>, InMemoryState {
    private boolean isCleared = true;
    private final KeyedCombineFn<? super K, InputT, AccumT, OutputT> combineFn;
    private AccumT accum;

    private InMemoryCombiningValue(KeyedCombineFn<? super K, InputT, AccumT, OutputT> combineFn) {
      this.combineFn = combineFn;
      accum = combineFn.createAccumulator(key);
    }

    @Override
    public void clear() {
      // Even though we're clearing we can't remove this from the in-memory state map, since
      // other users may already have a handle on this CombiningValue.
      accum = combineFn.createAccumulator(key);
      isCleared = true;
    }

    @Override
    public StateContents<OutputT> get() {
      return new StateContents<OutputT>() {
        @Override
        public OutputT read() {
          return combineFn.extractOutput(key, accum);
        }
      };
    }

    @Override
    public void add(InputT input) {
      isCleared = false;
      accum = combineFn.addInput(key, accum, input);
    }

    @Override
    public StateContents<AccumT> getAccum() {
      return new StateContents<AccumT>() {
        @Override
        public AccumT read() {
          return accum;
        }
      };
    }

    @Override
    public StateContents<Boolean> isEmpty() {
      return new StateContents<Boolean>() {
        @Override
        public Boolean read() {
          return isCleared;
        }
      };
    }

    @Override
    public void addAccum(AccumT accum) {
      isCleared = false;
      this.accum = combineFn.mergeAccumulators(key, Arrays.asList(this.accum, accum));
    }

    @Override
    public AccumT mergeAccumulators(Iterable<AccumT> accumulators) {
      return combineFn.mergeAccumulators(key, accumulators);
    }

    @Override
    public boolean isEmptyForTesting() {
      return isCleared;
    }
  }

  private static final class InMemoryBag<T> implements BagState<T>, InMemoryState {
    private List<T> contents = new ArrayList<>();

    @Override
    public void clear() {
      // Even though we're clearing we can't remove this from the in-memory state map, since
      // other users may already have a handle on this Bag.
      // The result of get/read below must be stable for the lifetime of the bundle within which it
      // was generated. In batch and direct runners the bundle lifetime can be
      // greater than the window lifetime, in which case this method can be called while
      // the result is still in use. We protect against this by hot-swapping instead of
      // clearing the contents.
      contents = new ArrayList<>();
    }

    @Override
    public StateContents<Iterable<T>> get() {
      return new StateContents<Iterable<T>>() {
        @Override
        public Iterable<T> read() {
          return contents;
        }
      };
    }

    @Override
    public void add(T input) {
      contents.add(input);
    }

    @Override
    public boolean isEmptyForTesting() {
      return contents.isEmpty();
    }

    @Override
    public StateContents<Boolean> isEmpty() {
      return new StateContents<Boolean>() {
        @Override
        public Boolean read() {
          return contents.isEmpty();
        }
      };
    }
  }
}
