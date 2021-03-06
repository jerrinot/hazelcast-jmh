/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.onheapslab;

import com.hazelcast.nio.BufferObjectDataOutput;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializableFactory;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.nio.serialization.SerializationServiceBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@State(Scope.Benchmark)
@Fork(jvmArgsPrepend = {"-Xmx25G", "-Xms15G", "-XX:+UseTLAB", "-XX:+AlwaysPreTouch"})
@OperationsPerInvocation(OnheapSlabBenchmark.DEFAULT_OPERATIONS_PER_INVOCATION)
public class OnheapSlabBenchmark {

    public static final int DEFAULT_OPERATIONS_PER_INVOCATION = 3221200;
    public static final int DEFAULT_NO_OF_SEGMENTS = 10;
    public static final int DEFAULT_CAPACITY_PER_SEGMENT = 1024 * 1024 * 1024;

    public static final int DEFAULT_MIN_OBJECT_SIZE = 1000;
    public static final int DEFAULT_MAX_OBJECT_SIZE = 2000;

    private static final long GB = 1024 * 1024 * 1024;
    private static final long MB = 1024 * 1024;

    private final Random random = new Random();

    private SerializationService serializationService;

    @Param(value = {"SLAB", "OFFHEAP", "JDK"})
    private String type;

    private Map<Integer, byte[]> map;
    private int minObjectSize;
    private int maxObjectSize;

    private int opsPerInvocation;

    @Setup(Level.Trial)
    public void benchmarkSetup(BenchmarkParams params) {
        serializationService = new SerializationServiceBuilder()
                .addDataSerializableFactory(1000, new EntityDataSerializableFactory())
                .setAllowUnsafe(true).setUseNativeByteOrder(true).build();
        if (params != null) {
            opsPerInvocation = params.getOpsPerInvocation();
        } else {
            opsPerInvocation = DEFAULT_OPERATIONS_PER_INVOCATION;
        }

        configureObjectSize();

        map = createMap();
    }

    private void configureObjectSize() {
        minObjectSize = Integer.getInteger("minObjectSize", DEFAULT_MIN_OBJECT_SIZE);
        maxObjectSize = Integer.getInteger("maxObjectSize", DEFAULT_MAX_OBJECT_SIZE);

        if (maxObjectSize < minObjectSize) {
            throw new RuntimeException("Max Object Size cannot be < Max Object Size. " +
                    "Configured Max Object Size: "+maxObjectSize+", Configured Min Object Size: "+minObjectSize);
        }

        if (maxObjectSize <= 0) {
            throw new RuntimeException("Max Object Size must be > 0. Configured Max Object size: "+maxObjectSize);
        }

        if (minObjectSize < 0) {
            throw new RuntimeException("Min Object Size must be >= 0. Configured Min Object size: "+minObjectSize);
        }
    }

    @TearDown(Level.Trial)
    public void benchmarkTeardown() {
        if (map instanceof SlapMap) {
            ((SlapMap) map).destroy();
        }
    }

    @TearDown(Level.Iteration)
    public void teardown() {
        map.clear();
    }

    public Map<Integer, byte[]> createMap() {
        Map<Integer, byte[]> map;
        if ("SLAB".equals(type)) {
            map = new SlapMap(false, opsPerInvocation + 100, getNoOfSegmets(), getCapacityPerSegment());
        } else if ("OFFHEAP".equals(type)) {
            map = new SlapMap(true, opsPerInvocation + 100, getNoOfSegmets(), getCapacityPerSegment());
        } else if ("JDK".equals(type)) {
            map = new HashMap<Integer, byte[]>();
        } else {
            throw new RuntimeException("Unknown map type");
        }
        return map;
    }

    private int getNoOfSegmets() {
        return Integer.getInteger("noOfSegments", DEFAULT_NO_OF_SEGMENTS);
    }

    private int getCapacityPerSegment() {
        String capacityString = System.getProperty("capacity");
        if (capacityString == null || capacityString.isEmpty()) {
            return DEFAULT_CAPACITY_PER_SEGMENT;
        }

        long totalCapacity;
        if (capacityString.endsWith("g")) {
            capacityString = capacityString.substring(0, capacityString.length() - 1);
            totalCapacity = Long.parseLong(capacityString) * GB;
        } else if (capacityString.endsWith("m")) {
            capacityString = capacityString.substring(0, capacityString.length() - 1);
            totalCapacity = Long.parseLong(capacityString) * MB;
        } else {
            totalCapacity = Long.parseLong(capacityString);
        }

        int noOfSegmets = getNoOfSegmets();
        long capacityPerSegment = (totalCapacity + noOfSegmets - 1) / noOfSegmets;
        if (capacityPerSegment > Integer.MAX_VALUE) {
            throw new RuntimeException("Capacity per segment cannot be more than Integer.MAX_VALUE. " +
                    "Total Capacity configured as "+totalCapacity+", no. of segments: "+ noOfSegmets +"" +
                    ". It means capacity per segment would be "+capacityPerSegment+" which is more than "+Integer.MAX_VALUE);
        }
        return (int) capacityPerSegment;
    }

    @Benchmark
    public long testInternal() {
        long h = 0;
        for (int i = 0; i < opsPerInvocation; i++) {
            byte[] entity = buildEntity();
            map.put(i, entity);
            byte[] e = map.get(i);
            h += e.length;
        }
        return h;
    }

    public static void main(String[] args) {
        OnheapSlabBenchmark benchmark = new OnheapSlabBenchmark();
        benchmark.type = "SLAB";
        benchmark.benchmarkSetup(null);
        for (int i = 0; i < 100; i++) {
            benchmark.testInternal();
            benchmark.teardown();
        }
        benchmark.benchmarkTeardown();
    }

    private byte[] buildEntity() {
        try {
            Entity entity = new Entity();
            entity.foo = new byte[minObjectSize + random.nextInt(maxObjectSize - minObjectSize + 1)];
            BufferObjectDataOutput objectDataOutput = serializationService.createObjectDataOutput(2100);
            objectDataOutput.writeObject(entity);
            byte[] buffer = objectDataOutput.getBuffer();
            byte[] temp = new byte[objectDataOutput.position()];
            System.arraycopy(buffer, 0, temp, 0, objectDataOutput.position());
            return temp;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Entity
            implements IdentifiedDataSerializable {

        private byte[] foo;

        @Override
        public void writeData(ObjectDataOutput objectDataOutput)
                throws IOException {

            objectDataOutput.writeInt(foo.length);
            objectDataOutput.write(foo);
        }

        @Override
        public void readData(ObjectDataInput objectDataInput)
                throws IOException {

            int length = objectDataInput.readInt();
            foo = new byte[length];
            objectDataInput.readFully(foo);
        }

        @Override
        public int getFactoryId() {
            return 1000;
        }

        @Override
        public int getId() {
            return 1000;
        }
    }

    private static final class EntityDataSerializableFactory
            implements DataSerializableFactory {

        @Override
        public IdentifiedDataSerializable create(int typeId) {
            return new Entity();
        }
    }
}
