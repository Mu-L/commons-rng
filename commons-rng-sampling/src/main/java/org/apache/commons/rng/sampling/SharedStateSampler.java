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

import org.apache.commons.rng.UniformRandomProvider;

/**
 * Applies to samplers that can share state between instances. Samplers can be created with a
 * new source of randomness that sample from the same state.
 *
 * @param <R> Type of the sampler.
 * @since 1.3
 */
@FunctionalInterface
public interface SharedStateSampler<R> {
    /**
     * Create a new instance of the sampler with the same underlying state using the given
     * uniform random provider as the source of randomness.
     *
     * @param rng Generator of uniformly distributed random numbers.
     * @return the sampler
     */
    R withUniformRandomProvider(UniformRandomProvider rng);
}
