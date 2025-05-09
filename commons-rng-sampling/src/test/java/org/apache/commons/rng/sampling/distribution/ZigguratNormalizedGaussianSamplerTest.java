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
package org.apache.commons.rng.sampling.distribution;

import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.sampling.RandomAssert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link ZigguratNormalizedGaussianSampler}.
 */
class ZigguratNormalizedGaussianSamplerTest {
    // Cf. RNG-56
    @Test
    void testInfiniteLoop() {
        // A bad implementation whose only purpose is to force access
        // to the rarest branch.
        // nextLong() returns Long.MAX_VALUE
        final UniformRandomProvider bad = () -> Long.MAX_VALUE;

        // Infinite loop (in v1.1).
        final ZigguratNormalizedGaussianSampler sampler = new ZigguratNormalizedGaussianSampler(bad);
        Assertions.assertThrows(StackOverflowError.class, sampler::sample);
    }

    /**
     * Test the SharedStateSampler implementation.
     */
    @Test
    void testSharedStateSampler() {
        final UniformRandomProvider rng1 = RandomAssert.seededRNG();
        final UniformRandomProvider rng2 = RandomAssert.seededRNG();
        final SharedStateContinuousSampler sampler1 =
            ZigguratNormalizedGaussianSampler.<ZigguratNormalizedGaussianSampler>of(rng1);
        final SharedStateContinuousSampler sampler2 = sampler1.withUniformRandomProvider(rng2);
        RandomAssert.assertProduceSameSequence(sampler1, sampler2);
    }
}
