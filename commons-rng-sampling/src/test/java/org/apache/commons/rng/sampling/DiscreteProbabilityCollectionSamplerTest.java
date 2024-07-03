/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.rng.sampling;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.rng.UniformRandomProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test class for {@link DiscreteProbabilityCollectionSampler}.
 */
class DiscreteProbabilityCollectionSamplerTest {
    /** RNG. */
    private final UniformRandomProvider rng = RandomAssert.createRNG();

    @Test
    void testPrecondition1() {
        // Size mismatch
        final List<Double> collection = Arrays.asList(1d, 2d);
        final double[] probabilities = {0};
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                 collection,
                 probabilities));
    }

    @Test
    void testPrecondition2() {
        // Negative probability
        final List<Double> collection = Arrays.asList(1d, 2d);
        final double[] probabilities = {0, -1};
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                collection,
                probabilities));
    }

    @Test
    void testPrecondition3() {
        // Probabilities do not sum above 0
        final List<Double> collection = Arrays.asList(1d, 2d);
        final double[] probabilities = {0, 0};
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                collection,
                probabilities));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.POSITIVE_INFINITY, Double.NaN})
    void testPrecondition4(double p) {
        final List<Double> collection = Arrays.asList(1d, 2d);
        final double[] probabilities = {0, p};
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                collection,
                probabilities));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.POSITIVE_INFINITY, Double.NaN})
    void testPrecondition5(double p) {
        final Map<String, Double> collection = new HashMap<>();
        collection.put("one", 0.0);
        collection.put("two", p);
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                collection));
    }

    @Test
    void testPrecondition6() {
        // Empty Map<T, Double> not allowed
        final Map<String, Double> collection = Collections.emptyMap();
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                 collection));
    }

    @Test
    void testPrecondition7() {
        // Empty List<T> not allowed
        final List<Double> collection = Collections.emptyList();
        final double[] probabilities = {};
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DiscreteProbabilityCollectionSampler<>(rng,
                collection,
                probabilities));
    }

    @Test
    void testSample() {
        final DiscreteProbabilityCollectionSampler<Double> sampler =
            new DiscreteProbabilityCollectionSampler<>(rng,
                                                       Arrays.asList(3d, -1d, 3d, 7d, -2d, 8d),
                                                       new double[] {0.2, 0.2, 0.3, 0.3, 0, 0});
        final double expectedMean = 3.4;
        final double expectedVariance = 7.84;

        final int n = 100000000;
        double sum = 0;
        double sumOfSquares = 0;
        for (int i = 0; i < n; i++) {
            final double rand = sampler.sample();
            sum += rand;
            sumOfSquares += rand * rand;
        }

        final double mean = sum / n;
        Assertions.assertEquals(expectedMean, mean, 1e-3);
        final double variance = sumOfSquares / n - mean * mean;
        Assertions.assertEquals(expectedVariance, variance, 2e-3);
    }


    @Test
    void testSampleUsingMap() {
        final UniformRandomProvider rng1 = RandomAssert.seededRNG();
        final UniformRandomProvider rng2 = RandomAssert.seededRNG();
        final List<Integer> items = Arrays.asList(1, 3, 4, 6, 9);
        final double[] probabilities = {0.1, 0.2, 0.3, 0.4, 0.5};
        final DiscreteProbabilityCollectionSampler<Integer> sampler1 =
            new DiscreteProbabilityCollectionSampler<>(rng1, items, probabilities);

        // Create a map version. The map iterator must be ordered so use a TreeMap.
        final Map<Integer, Double> map = new TreeMap<>();
        for (int i = 0; i < probabilities.length; i++) {
            map.put(items.get(i), probabilities[i]);
        }
        final DiscreteProbabilityCollectionSampler<Integer> sampler2 =
            new DiscreteProbabilityCollectionSampler<>(rng2, map);

        for (int i = 0; i < 50; i++) {
            Assertions.assertEquals(sampler1.sample(), sampler2.sample());
        }
    }

    /**
     * Edge-case test:
     * Create a sampler that will return 1 for nextDouble() forcing the search to
     * identify the end item of the cumulative probability array.
     */
    @Test
    void testSampleWithProbabilityAtLastItem() {
        // Ensure the samples pick probability 0 (the first item) and then
        // a probability (for the second item) that hits an edge case.
        final UniformRandomProvider dummyRng = new UniformRandomProvider() {
            private int count;

            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                // Return 0 then the 1.0 for the probability
                return (count++ == 0) ? 0 : 1.0;
            }
        };

        final List<Double> items = Arrays.asList(1d, 2d);
        final DiscreteProbabilityCollectionSampler<Double> sampler =
            new DiscreteProbabilityCollectionSampler<>(dummyRng,
                                                       items,
                                                       new double[] {0.5, 0.5});
        final Double item1 = sampler.sample();
        final Double item2 = sampler.sample();
        // Check they are in the list
        Assertions.assertTrue(items.contains(item1), "Sample item1 is not from the list");
        Assertions.assertTrue(items.contains(item2), "Sample item2 is not from the list");
        // Test the two samples are different items
        Assertions.assertNotSame(item1, item2, "Item1 and 2 should be different");
    }

    /**
     * Test the SharedStateSampler implementation.
     */
    @Test
    void testSharedStateSampler() {
        final UniformRandomProvider rng1 = RandomAssert.seededRNG();
        final UniformRandomProvider rng2 = RandomAssert.seededRNG();
        final List<Double> items = Arrays.asList(1d, 2d, 3d, 4d);
        final DiscreteProbabilityCollectionSampler<Double> sampler1 =
            new DiscreteProbabilityCollectionSampler<>(rng1,
                                                       items,
                                                       new double[] {0.1, 0.2, 0.3, 0.4});
        final DiscreteProbabilityCollectionSampler<Double> sampler2 = sampler1.withUniformRandomProvider(rng2);
        RandomAssert.assertProduceSameSequence(sampler1, sampler2);
    }
}
