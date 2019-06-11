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
package org.apache.commons.rng.core.source32;

import org.apache.commons.rng.core.RandomAssert;
import org.junit.Test;

public class Well1024aTest {
    /** The size of the array seed. */
    private static final int SEED_SIZE = 32;

    @Test
    public void testReferenceCode() {
        final Well1024a rng = new Well1024a(new int[] {
            0x2c2878c6, 0x47af36c4, 0xf422e677, 0xf08fd8d3, 0xee9a47c7, 0xba983942, 0xa2a9f9a5, 0x1d443748,
            0x8fc260b2, 0x5275c681, 0x4a2f5a28, 0x2911683d, 0xa204c27e, 0xb20a6a26, 0x54ba33be, 0x67d63eb0,
            0xdc8174cf, 0x3e73a4bc, 0x6fce0775, 0x9e6141fc, 0x5232218a, 0x0fa9e601, 0x0b6fdb4a, 0xf10a0a8c,
            0x97829dba, 0xc60b0778, 0x0566db41, 0x620807aa, 0x599b89c9, 0x1a34942b, 0x6baae3da, 0x4ba0b73d
        });

        final int[] expectedSequence = {
            0xa7dc11e2, 0x9dea7324, 0x844c7605, 0x85025732, 0x92ad1e10, 0x968e8090, 0xfd92cb4e, 0x665e1202,
            0x7eff3e03, 0x2eb25d85, 0x22002049, 0x5cfc119b, 0x26ef8f33, 0x519448b2, 0xfbb4d089, 0x3fd7de78,
            0x37a84c6c, 0x018e7b90, 0x02f93e0a, 0x6bc587fb, 0x4125b170, 0x2cfe1251, 0x4fb0ea3c, 0x8989e9b0,
            0xd6cd467e, 0x947b1d89, 0x423431c2, 0x45eeaa79, 0xe8b1d00e, 0x780e82cc, 0x0ac61f4d, 0xe92d8bfb,
            0x8e43df27, 0x5e38245d, 0x406394b5, 0xf88487a8, 0x7cd7febf, 0x7f227485, 0xe5db8d04, 0xd2aec04b,
            0x3f1292ed, 0x7a3cfb20, 0xa48a7893, 0xfc458532, 0x31253d6e, 0xcf354d5d, 0x9145cf5d, 0xd72f590e,
            0xf6ab2301, 0xca30f9c5, 0xcb8021c6, 0xad4eb3a4, 0xb4b7e1d5, 0x1ab409c5, 0x0bfb99ba, 0x7306d009,
            0xe4dba576, 0x281c99d3, 0x7736b135, 0xa3cd1046, 0x1a9a9fe2, 0x3bb4adae, 0xd183615c, 0xeb462c96,
            0xaff62ad3, 0x61b9dece, 0xce3617d5, 0x59bd68e0, 0x15e00d2f, 0x86a72cac, 0x958249bf, 0x4a7d49f1,
            0x0adfbdf0, 0x56198ad1, 0x6c33cb4c, 0x4f7fc05e, 0x11dc8281, 0xef07f51f, 0x7942882e, 0x54d60027,
            0xb160dd94, 0x5cd24e29, 0xe576d046, 0xf0fdb2fa, 0x5f88934b, 0x3844da1e, 0xc32bf41e, 0x0f66052b,
            0x28d826df, 0x6d9c60cf, 0xf6a95620, 0xc59a67e6, 0xdfe9ff7c, 0x0dfc5eea, 0x0c95ece6, 0xda1f0f70,
            0xc234b213, 0xafad6be5, 0xde497dae, 0xaf03aacb, 0x1e50e6f3, 0xd12106eb, 0x7b77d295, 0x47f0b2e4,
            0xd78853fe, 0x09fec179, 0x089fedc5, 0x6680db4d, 0x5deddb60, 0xaff0127d, 0x96b5cec8, 0x10fca09f,
            0xd53ec956, 0x3534d053, 0xae70ae3d, 0xdb4d222c, 0xd47770c7, 0x5115fee3, 0x9094ef39, 0x69fe3b7c,
            0xdd116917, 0xd64e3746, 0x03aae089, 0x91195149, 0xe1069acc, 0x6dbcbde5, 0x5cb8b9ed, 0xe828ccb7,
            0xd0e447f2, 0x192ca3eb, 0x77af1ef9, 0x79e37fe6, 0x99f2710d, 0xf9dda18a, 0xd6a47494, 0x8f1b3489,
            0xb3682658, 0xf321e2be, 0x05b64ca4, 0x60e803b8, 0xc10f74c0, 0x1e94c84d, 0x6ccb0d5e, 0xdf02d86c,
            0x8d5bd3f9, 0x9091fef2, 0x21cf487c, 0x9796c3d2, 0x92c94ca9, 0xf98df7f9, 0xb1c8be62, 0x00d5ba0b,
            0x32ad5936, 0xe935321c, 0x9831f624, 0xb179166d, 0x7420ecf6, 0x2cf10ae7, 0x3d49a2ab, 0x146a0bb4,
            0x910037cb, 0x2b24721a, 0xf2098316, 0x5ff58eb1, 0xd0274270, 0x4e6e006b, 0x5598bbb5, 0x490076a0,
            0x7fd35adf, 0x92545942, 0x0d667f1a, 0xa8e04323, 0xbf9a9b38, 0x61aaa5ba, 0xb92de80c, 0xec9e1fad,
            0x97a6cd05, 0x95e10296, 0x29a6bd92, 0xc9dba5cc, 0x11ddc4b2, 0xf65d3ffa, 0x73861431, 0x2fb3902d,
            0x03604221, 0xb7959946, 0xb59b2056, 0x6ca5ac44, 0x69f44409, 0xd52c49f2, 0x71bc35d5, 0x674c4b61,
            0x6e60e6eb, 0x4c80f38f, 0x966921d4, 0x7acbda1f, 0x634f1d39, 0x7268895b, 0xe24ee616, 0x29940720,
            0x6e987cac, 0xa165ce9d, 0x21b084b2, 0xa7e95f53, 0xc8d38139, 0xcfede657, 0xb8637eb9, 0xab175528,
            0x6a9c1f4b, 0x04232f3d, 0x27182484, 0xb9e7fdc4, 0x017a3a38, 0xcca5ca4f, 0x1a32a17a, 0xf4a6386a,
            0xe91f7fea, 0xe1af8929, 0x55ed33ef, 0x24401c76, 0x5bc26738, 0x37302912, 0x298e9336, 0x6ff45481,
            0x8ab94db8, 0x9d1353bd, 0xf11e84b9, 0x327daa22, 0x17b50f84, 0xf8878bf3, 0xb34c5e65, 0x3c95fa54,
            0xc4843530, 0x6b029593, 0x6ead61b5, 0x99acd553, 0xdd4c9e2d, 0xcb200d79, 0x1972f777, 0xdbb00a9b,
            0xa933a748, 0xe32edea0, 0x3fbb9fd2, 0x39f33c90, 0xac262539, 0x747aa26d, 0x032309b3, 0x48492c89,
            0x7e3584fc, 0x10c0d6f0, 0x2c32f649, 0xe335092e, 0x6e5296ae, 0xf1677c99, 0xefdba4a4, 0x6c55e637
        };

        RandomAssert.assertEquals(expectedSequence, rng);
    }

    @Test
    public void testConstructorWithZeroSeedIsNonFunctional() {
        RandomAssert.assertNextIntZeroOutput(new Well1024a(new int[SEED_SIZE]), 2 * SEED_SIZE);
    }

    @Test
    public void testConstructorWithSingleBitSeedIsFunctional() {
        RandomAssert.assertIntArrayConstructorWithSingleBitSeedIsFunctional(Well1024a.class, SEED_SIZE);
    }
}
