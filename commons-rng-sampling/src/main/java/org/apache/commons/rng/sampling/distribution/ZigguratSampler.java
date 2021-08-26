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

/**
 * Modified ziggurat method for sampling from Gaussian and exponential distributions.
 *
 * <p>Uses the algorithm from:
 *
 * <blockquote>
 * McFarland, C.D. (2016)<br>
 * "A modified ziggurat algorithm for generating exponentially and normally distributed pseudorandom numbers".<br>
 * <i>Journal of Statistical Computation and Simulation</i> <b>86</b>, 1281-1294.
 * </blockquote>
 *
 * <p>Note: The algorithm is a modification of the
 * {@link ZigguratNormalizedGaussianSampler Marsaglia and Tsang "Ziggurat" method}.
 * The modification improves performance by:
 * <ol>
 * <li>Creating layers of the ziggurat entirely inside the probability density function (area B);
 * this allows the majority of samples to be obtained without checking if the value is in the
 * region of the ziggurat layer that requires a rejection test.
 * <li>For samples not within the main ziggurat (area A) alias sampling is used to choose a
 * layer and rejection of points above the PDF is accelerated using precomputation of
 * triangle regions entirely below or above the curve.
 * </ol>
 *
 * <pre>
 *           \
 * ----------+\
 *           | \
 *    B      |A \
 * -------------+\
 *              | \
 * </pre>
 *
 * <p>Sampling uses {@link UniformRandomProvider#nextLong()}.
 *
 * @see <a href="https://www.tandfonline.com/doi/abs/10.1080/00949655.2015.1060234">
 * McFarland (2016) JSCS 86, 1281-1294</a>
 * @since 1.4
 */
public abstract class ZigguratSampler implements SharedStateContinuousSampler {
    /** Mask to extract the lowest 8-bits from an integer. */
    private static final int MASK_INT8 = 0xff;
    /** Mask to create an unsigned long from a signed long. This is the maximum value of a 64-bit long. */
    private static final long MAX_INT64 = Long.MAX_VALUE;
    /** 2^63. */
    private static final double TWO_POW_63 = 0x1.0p63;

    /** Underlying source of randomness. */
    private final UniformRandomProvider rng;

    // =========================================================================
    // Implementation note:
    //
    // This has been adapted from the reference c implementation provided
    // by C.D. McFarland:
    //
    // https://github.com/cd-mcfarland/fast_prng
    //
    // The adaption was based on the reference as of July-2021.
    // The code uses similar naming conventions from the exponential.h and normal.h
    // reference. Naming has been updated to be consistent in the exponential and normal
    // samplers. Comments from the c source have been included.
    // Branch frequencies have been measured and added as comments.
    //
    // Notable changes based on performance tests across JDKs and platforms:
    // The generation of unsigned longs has been changed to use bit shifts to favour
    // the significant bits of the long. The interpolation of X and Y uses a single method.
    // Recursion in the exponential sampler has been avoided.
    //
    // Note: The c implementation uses a RNG where the current value can be obtained
    // without advancing the generator. The entry point to the sample generation
    // always has this value as a previously unused value. The RNG is advanced when new
    // bits are required. This Java implementation will generate new values with calls
    // to the RNG and cache the value if it is to be recycled.
    //
    // The script used to generate the tables has been modified to scale values by 2^63
    // or 2^64 instead of 2^63 - 1 and 2^64 - 1. This allows a random 64-bit long to
    // represent a uniform value in [0, 1) as the numerator of a fraction with a value of
    // [0, 2^63) / 2^63 or [0, 2^64) / 2^64 respectively (the denominator is assumed).
    // Scaling of the high precision float values in the script is exact before
    // conversion to integers.
    //
    // Entries in the probability alias table are always compared to a long with the same
    // lower 8-bits since these bits identify the index in the table.
    // The entries in the IPMF tables have had the lower 8-bits set to zero. If these bits
    // are >= 128 then 256 is added to the alias table to round the number. The alias table
    // thus represents the numerator of a fraction with an unsigned magnitude of [0, 2^56 - 1)
    // and denominator 2^56. The numerator is effectively left-shifted 8 bits and 2^63 is
    // subtracted to store the value using a signed 64-bit long.
    //
    // Computation of these tables is dependent on the platform used to run the python script.
    // The X and Y tables are identical to 1 ULP. The MAP is identical. The IPMF table is computed
    // using rebalancing of the overhang probabilities to create the alias map. The table has
    // been observed to exhibit differences in the last 7 bits of the 56 bits used (ignoring the
    // final 8 bits) for the exponential and 11 bits for the normal. This corresponds to a
    // probability of 2^-49 (1.78e-15), or 2^-45 (2.84e-14) respectively. The tables may be
    // regenerated in future versions if the reference script receives updates to improve
    // accuracy.
    //
    // Method Description
    //
    // The ziggurat is constructed using layers that fit exactly within the probability density
    // function. Each layer has the same area. This area is chosen to be a fraction of the total
    // area under the PDF with the denominator of the fraction a power of 2. These tables
    // use 1/256 as the volume of each layer. The remaining part of the PDF that is not represented
    // by the layers is the overhang. There is an overhang above each layer and a final tail.
    // The following is a ziggurat with 3 layers:
    //
    //     Y3 |\
    //        | \  j=3
    //        |  \
    //     Y2 |   \
    //        |----\
    //        |    |\
    //        | i=2| \ j=2
    //        |    |  \
    //     Y1 |--------\
    //        | i=1   | \ j=1
    //        |       |  \
    //     Y0 |-----------\
    //        | i=0      | \ j=0 (tail)
    //        +--------------
    //        X3  |   |  X0
    //            |   X1
    //            X2
    //
    // There are N layers referenced using i in [0, N). The overhangs are referenced using
    // j in [1, N]; j=0 is the tail. Note that N is < 256.
    // Information about the ziggurat is pre-computed:
    // X = The length of each layer (supplemented with zero for Xn)
    // Y = PDF(X) for each layer (supplemented with PDF(x=0) for Yn)
    //
    // Sampling is performed as:
    // - Pick index i in [0, 256).
    // - If i is a layer then return a uniform deviate multiplied by the layer length
    // - If i is not a layer then sample from the overhang or tail
    //
    // The overhangs and tail have different volumes. Sampling must pick a region j based the
    // probability p(j) = vol(j) / sum (vol(j)). This is performed using alias sampling.
    // (See Walker, AJ (1977) "An Efficient Method for Generating Discrete Random Variables with
    // General Distributions" ACM Transactions on Mathematical Software 3 (3), 253-256.)
    // This uses a table that has been constructed to evenly balance A categories with
    // probabilities around the mean into B sections each allocated the 'mean'. For the 4
    // regions in the ziggurat shown above balanced into 8 sections:
    //
    // 3
    // 3
    // 32
    // 32
    // 321
    // 321   => 31133322
    // 3210     01233322
    //
    // section  abcdefgh
    //
    // A section with an index below the number of categories represents the category j and
    // optionally an alias. Sections with an index above the number
    // of categories are entirely filled with the alias. The region is chosen
    // by selecting a section and then checking if a uniform deviate is above the alias
    // threshold. If so then the alias is used in place of the original index.
    //
    // Alias sampling uses a table size of 256. This allows fast computation of the index
    // as a power of 2. The probability threshold is stored as the numerator of a fraction
    // allowing direct comparison with a uniform long deviate.
    //
    // MAP = Alias map for j in [0, 256)
    // IPMF = Alias probability threshold for j
    //
    // Note: The IPMF table is larger than the number of regions. Thus the final entries
    // must represent a probability of zero so that the alias is always used.
    //
    // If the selected region j is the tail then sampling uses a sampling method appropriate
    // for the PDF. If the selected region is an overhang then sampling generates a random
    // coordinate inside the rectangle covering the overhang using random deviates u1 and u2:
    //
    //    X[j],Y[j]
    //        |\-->u1
    //        | \  |
    //        |  \ |
    //        |   \|    Overhang j (with hypotenuse not pdf(x))
    //        |    \
    //        |    |\
    //        |    | \
    //        |    u2 \
    //        +-------- X[j-1],Y[j-1]
    //
    // The random point (x,y) has coordinates:
    // x = X[j] + u1 * (X[j-1] - X[j])
    // y = Y[j] + u2 * (Y[j-1] - Y[j])
    //
    // The expressions can be evaluated from the opposite direction using (1-u), e.g:
    // y = Y[j-1] + (1-u2) * (Y[j] - Y[j-1])
    // This allows the large value to subtract the small value before multiplying by u.
    // This method is used in the reference c code. It uses an addition subtraction to create 1-u.
    // Note that the tables X and Y have been scaled by 2^-63. This allows U to be a uniform
    // long in [0, 2^63). Thus the value c in 'c + m * x' must be scaled up by 2^63.
    //
    // If point (x,y) is below pdf(x) then the sample is accepted.
    // If u2 > u1 then the point is below the hypotenuse.
    // If u1 > u2 then the point is above the hypotenuse.
    // The distance above/below the hypotenuse is the difference u2 - u1: negative is above;
    // positive is below.
    //
    // The pdf(x) may lie completely above or below the hypotenuse. If the region under the pdf
    // is inside then this is referred to as convex (above) and concave (below). The
    // exponential function is concave for all regions. The normal function is convex below
    // x=1, and concave above x=1. x=1 is the point of inflection.
    //
    //        Concave                   Convex
    //        |-                        |----
    //        | -                       |    ---
    //        |  -                      |       --
    //        |   --                    |         --
    //        |     --                  |           -
    //        |       ---               |            -
    //        |          ----           |             -
    //
    // Optimisations:
    //
    // Regions that are concave can detect a point (x,y) above the hypotenuse and reflect the
    // point in the hypotenuse by swapping u1 and u2.
    //
    // Regions that are convex can detect a point (x,y) below the hypotenuse and immediate accept
    // the sample.
    //
    // The maximum distance of pdf(x) from the hypotenuse can be precomputed. This can be done for
    // each region or by taking the largest distance across all regions. This value can be
    // compared to the distance between u1 and u2 and the point immediately accepted (concave)
    // or rejected (convex) as it is known to be respectively inside or outside the pdf.
    // This sampler uses a single value for the maximum distance of pdf(x) from the hypotenuse.
    // For the normal distribution this is two values to separate the maximum for convex and
    // concave regions.
    // =========================================================================

    /**
     * Modified ziggurat method for sampling from an exponential distributions.
     */
    public static class Exponential extends ZigguratSampler {
        // Ziggurat volumes:
        // Inside the layers              = 98.4375%  (252/256)
        // Fraction outside the layers:
        // concave overhangs              = 96.6972%
        // tail                           =  3.3028%

        /** The number of layers in the ziggurat. Maximum i value for early exit. */
        private static final int I_MAX = 252;
        /** Maximum deviation of concave pdf(x) below the hypotenuse value for early exit.
         * Equal to approximately 0.0926 scaled by 2^63. */
        private static final long E_MAX = 853965788476313646L;
        /** Beginning of tail. Equal to X[0] * 2^63. */
        private static final double X_0 = 7.569274694148063;

        /** The alias map. An integer in [0, 255] stored as a byte to save space.
         * Contains the alias j for each index. j=0 is the tail; j in [1, N] is the overhang
         * for each layer. */
        private static final byte[] MAP = toBytes(new int[] {0, 0, 1, 235, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
            1, 1, 1, 2, 2, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252,
            252, 252, 252, 252, 252, 252, 252, 252, 252, 251, 251, 251, 251, 251, 251, 251, 251, 251, 251, 251, 251,
            251, 250, 250, 250, 250, 250, 250, 250, 249, 249, 249, 249, 249, 249, 248, 248, 248, 248, 247, 247, 247,
            247, 246, 246, 246, 245, 245, 244, 244, 243, 243, 242, 241, 241, 240, 239, 237, 3, 3, 4, 4, 6, 0, 0, 0, 0,
            236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 2, 0, 0, 0});
        /** The alias inverse PMF. This is the probability threshold to use the alias for j in-place of j.
         * This has been scaled by 2^64 and offset by -2^63. It represents the numerator of a fraction
         * with denominator 2^64 and can be compared directly to a uniform long deviate.
         * The value 0 is Long.MIN_VALUE and is used when {@code j > I_MAX}. */
        private static final long[] IPMF = {9223372036854774016L, 1623796909450834944L, 2664290944894291200L,
            7387971354164060928L, 6515064486552723200L, 8840508362680718848L, 6099647593382936320L,
            7673130333659513856L, 6220332867583438080L, 5045979640552813824L, 4075305837223955456L,
            3258413672162525440L, 2560664887087762432L, 1957224924672899584L, 1429800935350577408L, 964606309710808320L,
            551043923599587072L, 180827629096890368L, -152619738120023552L, -454588624410291456L, -729385126147774976L,
            -980551509819447040L, -1211029700667463936L, -1423284293868548352L, -1619396356369050368L,
            -1801135830956211712L, -1970018048575618048L, -2127348289059705344L, -2274257249303686400L,
            -2411729520096655360L, -2540626634159181056L, -2661705860113406464L, -2775635634532450560L,
            -2883008316030465280L, -2984350790383654912L, -3080133339198116352L, -3170777096303091200L,
            -3256660348483819008L, -3338123885075136256L, -3415475560473299200L, -3488994201966428160L,
            -3558932970354473216L, -3625522261068041216L, -3688972217741989376L, -3749474917563782656L,
            -3807206277531056128L, -3862327722496843520L, -3914987649156779776L, -3965322714631865344L,
            -4013458973776895488L, -4059512885612783360L, -4103592206186241024L, -4145796782586128128L,
            -4186219260694347008L, -4224945717447275264L, -4262056226866285568L, -4297625367836519680L,
            -4331722680528537344L, -4364413077437472512L, -4395757214229401600L, -4425811824915135744L,
            -4454630025296932608L, -4482261588141290496L, -4508753193105288192L, -4534148654077808896L,
            -4558489126279958272L, -4581813295192216576L, -4604157549138257664L, -4625556137145255168L,
            -4646041313519104512L, -4665643470413305856L, -4684391259530326528L, -4702311703971761664L,
            -4719430301145103360L, -4735771117539946240L, -4751356876102087168L, -4766209036859133952L,
            -4780347871386013440L, -4793792531638892032L, -4806561113635132672L, -4818670716409306624L,
            -4830137496634465536L, -4840976719260837888L, -4851202804490348800L, -4860829371376460032L,
            -4869869278311657472L, -4878334660640771072L, -4886236965617427200L, -4893586984900802560L,
            -4900394884772702720L, -4906670234238885376L, -4912422031164496896L, -4917658726580119808L,
            -4922388247283532288L, -4926618016851066624L, -4930354975163335168L, -4933605596540651264L,
            -4936375906575303936L, -4938671497741366016L, -4940497543854575616L, -4941858813449629440L,
            -4942759682136114944L, -4943204143989086720L, -4943195822025528064L, -4942737977813206528L,
            -4941833520255033344L, -4940485013586738944L, -4938694684624359424L, -4936464429291795968L,
            -4933795818458825728L, -4930690103114057984L, -4927148218896864000L, -4923170790008275968L,
            -4918758132519213568L, -4913910257091645696L, -4908626871126539264L, -4902907380349533952L,
            -4896750889844272896L, -4890156204540531200L, -4883121829162554368L, -4875645967641781248L,
            -4867726521994927104L, -4859361090668103424L, -4850546966345113600L, -4841281133215539200L,
            -4831560263698491904L, -4821380714613447424L, -4810738522790066176L, -4799629400105481984L,
            -4788048727936307200L, -4775991551010514944L, -4763452570642114304L, -4750426137329494528L,
            -4736906242696389120L, -4722886510751377664L, -4708360188440089088L, -4693320135461421056L,
            -4677758813316108032L, -4661668273553489152L, -4645040145179241472L, -4627865621182772224L,
            -4610135444140930048L, -4591839890849345536L, -4572968755929961472L, -4553511334358205696L,
            -4533456402849101568L, -4512792200036279040L, -4491506405372580864L, -4469586116675402496L,
            -4447017826233107968L, -4423787395382284800L, -4399880027458416384L, -4375280239014115072L,
            -4349971829190472192L, -4323937847117721856L, -4297160557210933504L, -4269621402214949888L,
            -4241300963840749312L, -4212178920821861632L, -4182234004204451584L, -4151443949668877312L,
            -4119785446662287616L, -4087234084103201536L, -4053764292396156928L, -4019349281473081856L,
            -3983960974549692672L, -3947569937258423296L, -3910145301787345664L, -3871654685619032064L,
            -3832064104425388800L, -3791337878631544832L, -3749438533114327552L, -3706326689447984384L,
            -3661960950051848192L, -3616297773528534784L, -3569291340409189376L, -3520893408440946176L,
            -3471053156460654336L, -3419717015797782528L, -3366828488034805504L, -3312327947826460416L,
            -3256152429334010368L, -3198235394669719040L, -3138506482563172864L, -3076891235255162880L,
            -3013310801389730816L, -2947681612411374848L, -2879915029671670784L, -2809916959107513856L,
            -2737587429961866240L, -2662820133571325696L, -2585501917733380096L, -2505512231579385344L,
            -2422722515205211648L, -2336995527534088448L, -2248184604988727552L, -2156132842510765056L,
            -2060672187261025536L, -1961622433929371904L, -1858790108950105600L, -1751967229002895616L,
            -1640929916937142784L, -1525436855617582592L, -1405227557075253248L, -1280020420662650112L,
            -1149510549536596224L, -1013367289578704896L, -871231448632104192L, -722712146453667840L,
            -567383236774436096L, -404779231966938368L, -234390647591545856L, -55658667960119296L, 132030985907841280L,
            329355128892811776L, 537061298001085184L, 755977262693564160L, 987022116608033280L, 1231219266829431296L,
            1489711711346518528L, 1763780090187553792L, 2054864117341795072L, 2364588157623768832L,
            2694791916990503168L, 3047567482883476224L, 3425304305830816256L, 3830744187097297920L,
            4267048975685830400L, 4737884547990017280L, 5247525842198998272L, 5800989391535355392L,
            6404202162993295360L, 7064218894258540544L, 7789505049452331520L, 8590309807749444864L,
            7643763810684489984L, 8891950541491446016L, 5457384281016206080L, 9083704440929284096L,
            7976211653914433280L, 8178631350487117568L, 2821287825726744832L, 6322989683301709568L,
            4309503753387611392L, 4685170734960170496L, 8404845967535199744L, 7330522972447554048L,
            1960945799076992000L, 4742910674644899072L, -751799822533509888L, 7023456603741959936L,
            3843116882594676224L, 3927231442413903104L, -9223372036854775808L, -9223372036854775808L,
            -9223372036854775808L};
        /**
         * The precomputed ziggurat lengths, denoted X_i in the main text.
         * <ul>
         * <li>X_i = length of ziggurat layer i.
         * <li>X_j is the upper-left X coordinate of overhang j (starting from 1).
         * <li>X_(j-1) is the lower-right X coordinate of overhang j.
         * </ul>
         * <p>Values have been scaled by 2^-63.
         * Contains {@code I_MAX + 1} entries as the final value is 0.
         */
        private static final double[] X = {8.206624067534882E-19, 7.397373235160728E-19, 6.913331337791529E-19,
            6.564735882096453E-19, 6.291253995981851E-19, 6.065722412960496E-19, 5.873527610373727E-19,
            5.705885052853694E-19, 5.557094569162239E-19, 5.423243890374395E-19, 5.301529769650878E-19,
            5.189873925770806E-19, 5.086692261799833E-19, 4.990749293879647E-19, 4.901062589444954E-19,
            4.816837901064919E-19, 4.737423865364471E-19, 4.662279580719682E-19, 4.590950901778405E-19,
            4.523052779065815E-19, 4.458255881635396E-19, 4.396276312636838E-19, 4.336867596710647E-19,
            4.2798143618469714E-19, 4.224927302706489E-19, 4.172039125346411E-19, 4.1210012522465616E-19,
            4.0716811225869233E-19, 4.0239599631006903E-19, 3.9777309342877357E-19, 3.93289757853345E-19,
            3.8893725129310323E-19, 3.8470763218720385E-19, 3.8059366138180143E-19, 3.765887213854473E-19,
            3.7268674692030177E-19, 3.688821649224816E-19, 3.651698424880007E-19, 3.6154504153287473E-19,
            3.5800337915318032E-19, 3.545407928453343E-19, 3.5115350988784242E-19, 3.478380203003096E-19,
            3.4459105288907336E-19, 3.4140955396563316E-19, 3.3829066838741162E-19, 3.3523172262289E-19,
            3.3223020958685874E-19, 3.292837750280447E-19, 3.263902052820205E-19, 3.2354741622810815E-19,
            3.207534433108079E-19, 3.180064325047861E-19, 3.1530463211820845E-19, 3.1264638534265134E-19,
            3.100301234693421E-19, 3.07454359701373E-19, 3.049176835000556E-19, 3.0241875541094565E-19,
            2.999563023214455E-19, 2.975291131074259E-19, 2.9513603463113224E-19, 2.9277596805684267E-19,
            2.9044786545442563E-19, 2.8815072666416712E-19, 2.858835963990693E-19, 2.8364556156331615E-19,
            2.81435748767798E-19, 2.7925332202553125E-19, 2.770974806115288E-19, 2.7496745707320232E-19,
            2.7286251537873397E-19, 2.7078194919206054E-19, 2.687250802641905E-19, 2.666912569315344E-19,
            2.646798527127889E-19, 2.6269026499668434E-19, 2.6072191381359757E-19, 2.5877424068465143E-19,
            2.568467075424817E-19, 2.549387957183548E-19, 2.530500049907748E-19, 2.511798526911271E-19,
            2.4932787286227806E-19, 2.474936154663866E-19, 2.456766456384867E-19, 2.438765429826784E-19,
            2.4209290090801527E-19, 2.403253260014054E-19, 2.3857343743505147E-19, 2.368368664061465E-19,
            2.3511525560671253E-19, 2.3340825872163284E-19, 2.3171553995306794E-19, 2.3003677356958333E-19,
            2.283716434784348E-19, 2.2671984281957174E-19, 2.250810735800194E-19, 2.234550462273959E-19,
            2.2184147936140775E-19, 2.2024009938224424E-19, 2.186506401748684E-19, 2.1707284280826716E-19,
            2.1550645524878675E-19, 2.1395123208673778E-19, 2.124069342755064E-19, 2.1087332888245875E-19,
            2.0935018885097035E-19, 2.0783729277295508E-19, 2.0633442467130712E-19, 2.0484137379170616E-19,
            2.0335793440326865E-19, 2.018839056075609E-19, 2.0041909115551697E-19, 1.9896329927183254E-19,
            1.975163424864309E-19, 1.9607803747261946E-19, 1.9464820489157862E-19, 1.9322666924284314E-19,
            1.9181325872045647E-19, 1.904078050744948E-19, 1.8901014347767504E-19, 1.8762011239677479E-19,
            1.8623755346860768E-19, 1.8486231138030984E-19, 1.8349423375370566E-19, 1.8213317103353295E-19,
            1.8077897637931708E-19, 1.7943150556069476E-19, 1.7809061685599652E-19, 1.7675617095390567E-19,
            1.7542803085801941E-19, 1.741060617941453E-19, 1.727901311201724E-19, 1.7148010823836362E-19,
            1.7017586450992059E-19, 1.6887727317167824E-19, 1.6758420925479093E-19, 1.6629654950527621E-19,
            1.650141723062866E-19, 1.6373695760198277E-19, 1.624647868228856E-19, 1.6119754281258616E-19,
            1.5993510975569615E-19, 1.586773731069231E-19, 1.5742421952115544E-19, 1.5617553678444595E-19,
            1.5493121374578016E-19, 1.5369114024951992E-19, 1.524552070684102E-19, 1.5122330583703858E-19,
            1.499953289856356E-19, 1.4877116967410352E-19, 1.4755072172615974E-19, 1.4633387956347966E-19,
            1.4512053813972103E-19, 1.439105928743099E-19, 1.4270393958586506E-19, 1.415004744251338E-19,
            1.4030009380730888E-19, 1.3910269434359025E-19, 1.3790817277185197E-19, 1.3671642588626657E-19,
            1.3552735046573446E-19, 1.3434084320095729E-19, 1.3315680061998685E-19, 1.3197511901207148E-19,
            1.3079569434961214E-19, 1.2961842220802957E-19, 1.28443197683331E-19, 1.2726991530715219E-19,
            1.2609846895903523E-19, 1.2492875177568625E-19, 1.237606560569394E-19, 1.225940731681333E-19,
            1.2142889343858445E-19, 1.2026500605581765E-19, 1.1910229895518744E-19, 1.1794065870449425E-19,
            1.1677997038316715E-19, 1.1562011745554883E-19, 1.144609816377787E-19, 1.1330244275772562E-19,
            1.1214437860737343E-19, 1.109866647870073E-19, 1.0982917454048923E-19, 1.086717785808435E-19,
            1.0751434490529747E-19, 1.0635673859884002E-19, 1.0519882162526621E-19, 1.0404045260457141E-19,
            1.0288148657544097E-19, 1.0172177474144965E-19, 1.0056116419943559E-19, 9.939949764834668E-20,
            9.823661307666745E-20, 9.70723434263201E-20, 9.590651623069063E-20, 9.47389532241542E-20,
            9.356946992015904E-20, 9.239787515456947E-20, 9.122397059055647E-20, 9.004755018085287E-20,
            8.886839958264763E-20, 8.768629551976745E-20, 8.6501005086071E-20, 8.531228498314119E-20,
            8.411988068438521E-20, 8.292352551651342E-20, 8.17229396480345E-20, 8.051782897283921E-20,
            7.930788387509923E-20, 7.809277785952443E-20, 7.687216602842904E-20, 7.564568338396512E-20,
            7.441294293017913E-20, 7.317353354509333E-20, 7.192701758763107E-20, 7.067292819766679E-20,
            6.941076623950036E-20, 6.813999682925642E-20, 6.686004537461023E-20, 6.557029304021008E-20,
            6.427007153336853E-20, 6.295865708092356E-20, 6.163526343814314E-20, 6.02990337321517E-20,
            5.894903089285018E-20, 5.758422635988593E-20, 5.62034866695974E-20, 5.480555741349931E-20,
            5.3389043909003295E-20, 5.1952387717989917E-20, 5.0493837866338355E-20, 4.901141522262949E-20,
            4.7502867933366117E-20, 4.5965615001265455E-20, 4.4396673897997565E-20, 4.279256630214859E-20,
            4.1149193273430015E-20, 3.9461666762606287E-20, 3.7724077131401685E-20, 3.592916408620436E-20,
            3.4067836691100565E-20, 3.2128447641564046E-20, 3.0095646916399994E-20, 2.794846945559833E-20,
            2.5656913048718645E-20, 2.317520975680391E-20, 2.042669522825129E-20, 1.7261770330213488E-20,
            1.3281889259442579E-20, 0.0};
        /**
         * The precomputed ziggurat heights, denoted Y_i in the main text.
         * <ul>
         * <li>Y_i = height of ziggurat layer i.
         * <li>Y_j is the upper-left Y coordinate of overhang j (starting from 1).
         * <li>Y_(j-1) is the lower-right Y coordinate of overhang j.
         * </ul>
         * <p>Values have been scaled by 2^-63.
         * Contains {@code I_MAX + 1} entries as the final value is pdf(x=0).
         */
        private static final double[] Y = {5.595205495112736E-23, 1.1802509982703313E-22, 1.844442338673583E-22,
            2.543903046669831E-22, 3.2737694311509334E-22, 4.0307732132706715E-22, 4.812547831949511E-22,
            5.617291489658331E-22, 6.443582054044353E-22, 7.290266234346368E-22, 8.156388845632194E-22,
            9.041145368348222E-22, 9.94384884863992E-22, 1.0863906045969114E-21, 1.1800799775461269E-21,
            1.2754075534831208E-21, 1.372333117637729E-21, 1.4708208794375214E-21, 1.5708388257440445E-21,
            1.6723581984374566E-21, 1.7753530675030514E-21, 1.8797999785104595E-21, 1.9856776587832504E-21,
            2.0929667704053244E-21, 2.201649700995824E-21, 2.311710385230618E-21, 2.4231341516125464E-21,
            2.535907590142089E-21, 2.650018437417054E-21, 2.765455476366039E-21, 2.8822084483468604E-21,
            3.000267975754771E-21, 3.1196254936130377E-21, 3.240273188880175E-21, 3.3622039464187092E-21,
            3.485411300740904E-21, 3.6098893927859475E-21, 3.735632931097177E-21, 3.862637156862005E-21,
            3.990897812355284E-21, 4.120411112391895E-21, 4.251173718448891E-21, 4.383182715163374E-21,
            4.5164355889510656E-21, 4.6509302085234806E-21, 4.7866648071096E-21, 4.923637966211997E-21,
            5.061848600747899E-21, 5.201295945443473E-21, 5.341979542364895E-21, 5.483899229483096E-21,
            5.627055130180635E-21, 5.7714476436191935E-21, 5.917077435895068E-21, 6.063945431917703E-21,
            6.212052807953168E-21, 6.3614009847804375E-21, 6.511991621413643E-21, 6.6638266093481696E-21,
            6.816908067292628E-21, 6.971238336352438E-21, 7.126819975634082E-21, 7.283655758242034E-21,
            7.441748667643017E-21, 7.601101894374635E-21, 7.761718833077541E-21, 7.923603079832257E-21,
            8.086758429783484E-21, 8.251188875036333E-21, 8.416898602810326E-21, 8.58389199383831E-21,
            8.752173620998646E-21, 8.921748248170071E-21, 9.09262082929965E-21, 9.264796507675128E-21,
            9.438280615393829E-21, 9.613078673021033E-21, 9.789196389431416E-21, 9.966639661827884E-21,
            1.0145414575932636E-20, 1.0325527406345955E-20, 1.0506984617068672E-20, 1.0689792862184811E-20,
            1.0873958986701341E-20, 1.10594900275424E-20, 1.1246393214695825E-20, 1.1434675972510121E-20,
            1.1624345921140471E-20, 1.181541087814266E-20, 1.2007878860214202E-20, 1.2201758085082226E-20,
            1.239705697353804E-20, 1.2593784151618565E-20, 1.2791948452935152E-20, 1.29915589211506E-20,
            1.3192624812605428E-20, 1.3395155599094805E-20, 1.3599160970797774E-20, 1.3804650839360727E-20,
            1.4011635341137284E-20, 1.4220124840587164E-20, 1.4430129933836705E-20, 1.46416614524042E-20,
            1.485473046709328E-20, 1.5069348292058084E-20, 1.5285526489044053E-20, 1.5503276871808626E-20,
            1.5722611510726402E-20, 1.5943542737583543E-20, 1.6166083150566702E-20, 1.6390245619451956E-20,
            1.6616043290999594E-20, 1.684348959456108E-20, 1.7072598247904713E-20, 1.7303383263267072E-20,
            1.7535858953637607E-20, 1.777003993928424E-20, 1.8005941154528286E-20, 1.8243577854777398E-20,
            1.8482965623825808E-20, 1.8724120381431627E-20, 1.8967058391181452E-20, 1.9211796268653192E-20,
            1.9458350989888484E-20, 1.9706739900186868E-20, 1.9956980723234356E-20, 2.0209091570579904E-20,
            2.0463090951473895E-20, 2.0718997783083593E-20, 2.097683140110135E-20, 2.123661157076213E-20,
            2.1498358498287976E-20, 2.1762092842777868E-20, 2.2027835728562592E-20, 2.229560875804522E-20,
            2.256543402504904E-20, 2.2837334128696004E-20, 2.311133218784001E-20, 2.3387451856080863E-20,
            2.366571733738611E-20, 2.394615340234961E-20, 2.422878540511741E-20, 2.451363930101321E-20,
            2.4800741664897764E-20, 2.5090119710298442E-20, 2.5381801309347597E-20, 2.56758150135705E-20,
            2.5972190075566336E-20, 2.6270956471628253E-20, 2.6572144925351523E-20, 2.687578693228184E-20,
            2.718191478565915E-20, 2.7490561603315974E-20, 2.7801761355793055E-20, 2.811554889573917E-20,
            2.8431959988666534E-20, 2.8751031345137833E-20, 2.907280065446631E-20, 2.9397306620015486E-20,
            2.9724588996191657E-20, 3.005468862722811E-20, 3.038764748786764E-20, 3.072350872605708E-20,
            3.1062316707775905E-20, 3.140411706412999E-20, 3.174895674085097E-20, 3.2096884050352357E-20,
            3.2447948726504914E-20, 3.280220198230601E-20, 3.315969657063137E-20, 3.352048684827223E-20,
            3.388462884347689E-20, 3.4252180327233346E-20, 3.4623200888548644E-20, 3.4997752014001677E-20,
            3.537589717186906E-20, 3.5757701901149035E-20, 3.61432339058358E-20, 3.65325631548274E-20,
            3.692576198788357E-20, 3.732290522808698E-20, 3.7724070301302117E-20, 3.812933736317104E-20,
            3.8538789434235234E-20, 3.895251254382786E-20, 3.93705958834424E-20, 3.979313197035144E-20,
            4.022021682232577E-20, 4.0651950144388133E-20, 4.1088435528630944E-20, 4.152978066823271E-20,
            4.197609758692658E-20, 4.242750288530745E-20, 4.2884118005513604E-20, 4.334606951598745E-20,
            4.381348941821026E-20, 4.428651547752084E-20, 4.476529158037235E-20, 4.5249968120658306E-20,
            4.574070241805442E-20, 4.6237659171683015E-20, 4.674101095281837E-20, 4.7250938740823415E-20,
            4.776763250705122E-20, 4.8291291852069895E-20, 4.8822126702292804E-20, 4.936035807293385E-20,
            4.990621890518202E-20, 5.045995498662554E-20, 5.1021825965285324E-20, 5.159210646917826E-20,
            5.2171087345169234E-20, 5.2759077033045284E-20, 5.335640309332586E-20, 5.396341391039951E-20,
            5.458048059625925E-20, 5.520799912453558E-20, 5.584639272987383E-20, 5.649611461419377E-20,
            5.715765100929071E-20, 5.783152465495663E-20, 5.851829876379432E-20, 5.921858155879171E-20,
            5.99330314883387E-20, 6.066236324679689E-20, 6.1407354758435E-20, 6.216885532049976E-20,
            6.294779515010373E-20, 6.37451966432144E-20, 6.456218773753799E-20, 6.54000178818891E-20,
            6.626007726330934E-20, 6.714392014514662E-20, 6.80532934473017E-20, 6.8990172088133E-20,
            6.99568031585645E-20, 7.095576179487843E-20, 7.199002278894508E-20, 7.306305373910546E-20,
            7.417893826626688E-20, 7.534254213417312E-20, 7.655974217114297E-20, 7.783774986341285E-20,
            7.918558267402951E-20, 8.06147755373533E-20, 8.214050276981807E-20, 8.378344597828052E-20,
            8.557312924967816E-20, 8.75544596695901E-20, 8.980238805770688E-20, 9.246247142115109E-20,
            9.591964134495172E-20, 1.0842021724855044E-19};

        /**
         * Specialisation which multiplies the standard exponential result by a specified mean.
         */
        private static class ExponentialMean extends Exponential {
            /** Mean. */
            private final double mean;

            /**
             * @param rng Generator of uniformly distributed random numbers.
             * @param mean Mean.
             */
            ExponentialMean(UniformRandomProvider rng, double mean) {
                super(rng);
                this.mean = mean;
            }

            @Override
            public double sample() {
                return super.sample() * mean;
            }

            @Override
            public ExponentialMean withUniformRandomProvider(UniformRandomProvider rng) {
                return new ExponentialMean(rng, this.mean);
            }
        }

        /**
         * @param rng Generator of uniformly distributed random numbers.
         */
        private Exponential(UniformRandomProvider rng) {
            super(rng);
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return toString("exponential");
        }

        /** {@inheritDoc} */
        @Override
        public double sample() {
            // Ideally this method byte code size should be below -XX:MaxInlineSize
            // (which defaults to 35 bytes). This compiles to 35 bytes.

            final long x = nextLong();
            // Float multiplication squashes these last 8 bits, so they can be used to sample i
            final int i = ((int) x) & MASK_INT8;

            if (i < I_MAX) {
                // Early exit.
                // Expected frequency = 0.984375
                // Drop the sign bit to multiply by [0, 2^63).
                return X[i] * (x >>> 1);
            }
            // Expected frequency = 0.015625

            // Tail frequency     = 0.000516062 (recursion)
            // Overhang frequency = 0.0151089

            // Recycle x as the upper 56 bits have not been used.
            return edgeSample(x);
        }

        /**
         * Create the sample from the edge of the ziggurat.
         *
         * <p>This method has been extracted to fit the main sample method within 35 bytes (the
         * default size for a JVM to inline a method).
         *
         * @param xx Initial random deviate
         * @return a sample
         */
        private double edgeSample(long xx) {
            int j = selectRegion();
            if (j != 0) {
                // Expected overhang frequency = 0.966972
                return sampleOverhang(j, xx);
            }
            // Expected tail frequency = 0.033028 (recursion)

            // xx must be discarded as the lower bits have already been used to generate i

            // If the tail then exploit the memoryless property of the exponential distribution.
            // Perform a new sample and add it to the start of the tail.
            // This loop sums tail values until a sample can be returned from the exponential.
            // The sum is added to the final sample on return.
            double x0 = X_0;
            for (;;) {
                // Duplicate of the sample() method
                final long x = nextLong();
                final int i = ((int) x) & 0xff;

                if (i < I_MAX) {
                    // Early exit.
                    return x0 + X[i] * (x >>> 1);
                }

                // Edge of the ziggurat
                j = selectRegion();
                if (j != 0) {
                    return x0 + sampleOverhang(j, x);
                }

                // Add another tail sample
                x0 += X_0;
            }
        }

        /**
         * Select the overhang region or the tail using alias sampling.
         *
         * @return the region
         */
        private int selectRegion() {
            final long x = nextLong();
            // j in [0, 256)
            final int j = ((int) x) & MASK_INT8;
            // map to j in [0, N] with N the number of layers of the ziggurat
            return x >= IPMF[j] ? MAP[j] & MASK_INT8 : j;
        }

        /**
         * Sample from overhang region {@code j}.
         *
         * @param j Index j (must be {@code > 0})
         * @param xx Initial random deviate
         * @return the sample
         */
        private double sampleOverhang(int j, long xx) {
            // Recycle the initial random deviate.
            // Shift right to make an unsigned long.
            long u1 = xx >>> 1;
            for (;;) {
                // Sample from the triangle:
                //    X[j],Y[j]
                //        |\-->u1
                //        | \  |
                //        |  \ |
                //        |   \|    Overhang j (with hypotenuse not pdf(x))
                //        |    \
                //        |    |\
                //        |    | \
                //        |    u2 \
                //        +-------- X[j-1],Y[j-1]
                // u2 = u1 + (u2 - u1) = u1 + uDistance
                // If u2 < u1 then reflect in the hypotenuse by swapping u1 and u2.
                long uDistance = randomInt63() - u1;
                if (uDistance < 0) {
                    // Upper-right triangle. Reflect in hypotenuse.
                    uDistance = -uDistance;
                    // Update u1 to be min(u1, u2) by subtracting the distance between them
                    u1 -= uDistance;
                }
                final double x = interpolate(X, j, u1);
                if (uDistance >= E_MAX) {
                    // Early Exit: x < y - epsilon
                    return x;
                }

                // Note: Frequencies have been empirically measured per call into expOverhang:
                // Early Exit = 0.823328
                // Accept Y   = 0.161930
                // Reject Y   = 0.0147417 (recursion)

                if (interpolate(Y, j, u1 + uDistance) <= Math.exp(-x)) {
                    return x;
                }

                // Generate another variate for the next iteration
                u1 = randomInt63();
            }
        }

        /** {@inheritDoc} */
        @Override
        public Exponential withUniformRandomProvider(UniformRandomProvider rng) {
            return new Exponential(rng);
        }

        /**
         * Create a new exponential sampler with {@code mean = 1}.
         *
         * @param rng Generator of uniformly distributed random numbers.
         * @return the sampler
         */
        public static Exponential of(UniformRandomProvider rng) {
            return new Exponential(rng);
        }

        /**
         * Create a new exponential sampler with the specified {@code mean}.
         *
         * @param rng Generator of uniformly distributed random numbers.
         * @param mean Mean.
         * @return the sampler
         * @throws IllegalArgumentException if the mean is not strictly positive ({@code mean <= 0})
         */
        public static Exponential of(UniformRandomProvider rng, double mean) {
            if (mean > 0) {
                return new ExponentialMean(rng, mean);
            }
            throw new IllegalArgumentException("Mean is not strictly positive: " + mean);
        }
    }

    /**
     * Modified ziggurat method for sampling from a Gaussian distribution with
     * mean 0 and standard deviation 1.
     *
     * <p>Note: The algorithm is a modification of the
     * {@link ZigguratNormalizedGaussianSampler Marsaglia and Tsang "Ziggurat" method}.
     * The modification improves performance of the rejection method used to generate
     * samples at the edge of the ziggurat.
     *
     * @see NormalizedGaussianSampler
     * @see GaussianSampler
     */
    public static final class NormalizedGaussian extends ZigguratSampler
        implements NormalizedGaussianSampler, SharedStateContinuousSampler {
        // Ziggurat volumes:
        // Inside the layers              = 98.8281%  (253/256)
        // Fraction outside the layers:
        // concave overhangs              = 76.1941%
        // inflection overhang            =  0.1358%
        // convex overhangs               = 21.3072%
        // tail                           =  2.3629%

        /** The number of layers in the ziggurat. Maximum i value for early exit. */
        private static final int I_MAX = 253;
        /** The point where the Gaussian switches from convex to concave.
         * This is the largest value of X[j] below 1. */
        private static final int J_INFLECTION = 204;
        /** Maximum epsilon distance of convex pdf(x) above the hypotenuse value for early rejection.
         * Equal to approximately 0.2460 scaled by 2^63. This is negated on purpose as the
         * distance for a point (x,y) above the hypotenuse is negative:
         * {@code (|d| < max) == (d >= -max)}. */
        private static final long CONVEX_E_MAX = -2269182951627976004L;
        /** Maximum distance of concave pdf(x) below the hypotenuse value for early exit.
         * Equal to approximately 0.08244 scaled by 2^63. */
        private static final long CONCAVE_E_MAX = 760463704284035184L;
        /** Beginning of tail. Equal to X[0] * 2^63. */
        private static final double X_0 = 3.6360066255009455861;
        /** 1/X_0. Used for tail sampling. */
        private static final double ONE_OVER_X_0 = 1.0 / X_0;

        /** The alias map. An integer in [0, 255] stored as a byte to save space.
         * Contains the alias j for each index. j=0 is the tail; j in [1, N] is the overhang
         * for each layer. */
        private static final byte[] MAP = toBytes(
            new int[] {0, 0, 239, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253, 253,
                253, 252, 252, 252, 252, 252, 252, 252, 252, 252, 252, 251, 251, 251, 251, 251, 251, 251, 250, 250, 250,
                250, 250, 249, 249, 249, 248, 248, 248, 247, 247, 247, 246, 246, 245, 244, 244, 243, 242, 240, 2, 2, 3,
                3, 0, 0, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 1, 0, 0});
        /** The alias inverse PMF. This is the probability threshold to use the alias for j in-place of j.
         * This has been scaled by 2^64 and offset by -2^63. It represents the numerator of a fraction
         * with denominator 2^64 and can be compared directly to a uniform long deviate.
         * The value 0 is Long.MIN_VALUE and is used when {@code j > I_MAX}. */
        private static final long[] IPMF = {9223372036854775296L, 1100243796534090752L, 7866600928998383104L,
            6788754710675124736L, 9022865200181688320L, 6522434035205502208L, 4723064097360024576L,
            3360495653216416000L, 2289663232373870848L, 1423968905551920384L, 708364817827798016L, 106102487305601280L,
            -408333464665794560L, -853239722779025152L, -1242095211825521408L, -1585059631105762048L,
            -1889943050287169024L, -2162852901990669824L, -2408637386594511104L, -2631196530262954496L,
            -2833704942520925696L, -3018774289025787392L, -3188573753472222208L, -3344920681707410944L,
            -3489349705062150656L, -3623166100042179584L, -3747487436868335360L, -3863276422712173824L,
            -3971367044063130880L, -4072485557029824000L, -4167267476830916608L, -4256271432240159744L,
            -4339990541927306752L, -4418861817133802240L, -4493273980372377088L, -4563574004462246656L,
            -4630072609770453760L, -4693048910430964992L, -4752754358862894848L, -4809416110052769536L,
            -4863239903586985984L, -4914412541515875840L, -4963104028439161088L, -5009469424769119232L,
            -5053650458856559360L, -5095776932695077632L, -5135967952544929024L, -5174333008451230720L,
            -5210972924952654336L, -5245980700100460288L, -5279442247516297472L, -5311437055462369280L,
            -5342038772315650560L, -5371315728843297024L, -5399331404632512768L, -5426144845448965120L,
            -5451811038519422464L, -5476381248265593088L, -5499903320558339072L, -5522421955752311296L,
            -5543978956085263616L, -5564613449659060480L, -5584362093436146432L, -5603259257517428736L,
            -5621337193070986240L, -5638626184974132224L, -5655154691220933888L, -5670949470294763008L,
            -5686035697601807872L, -5700437072199152384L, -5714175914219812352L, -5727273255295220992L,
            -5739748920271997440L, -5751621603810412032L, -5762908939773946112L, -5773627565915007744L,
            -5783793183152377600L, -5793420610475628544L, -5802523835894661376L, -5811116062947570176L,
            -5819209754516120832L, -5826816672854571776L, -5833947916825278208L, -5840613956570608128L,
            -5846824665591763456L, -5852589350491075328L, -5857916778480726528L, -5862815203334800384L,
            -5867292388935742464L, -5871355631762284032L, -5875011781262890752L, -5878267259039093760L,
            -5881128076579883520L, -5883599852028851456L, -5885687825288565248L, -5887396872144963840L,
            -5888731517955042304L, -5889695949247728384L, -5890294025706689792L, -5890529289910829568L,
            -5890404977675987456L, -5889924026487208448L, -5889089083913555968L, -5887902514965209344L,
            -5886366408898372096L, -5884482585690639872L, -5882252601321090304L, -5879677752995027712L,
            -5876759083794175232L, -5873497386318840832L, -5869893206505510144L, -5865946846617024256L,
            -5861658367354159104L, -5857027590486131456L, -5852054100063428352L, -5846737243971504640L,
            -5841076134082373632L, -5835069647234580480L, -5828716424754549248L, -5822014871949021952L,
            -5814963157357531648L, -5807559211080072192L, -5799800723447229952L, -5791685142338073344L,
            -5783209670985158912L, -5774371264582489344L, -5765166627072226560L, -5755592207057667840L,
            -5745644193442049280L, -5735318510777133824L, -5724610813433666560L, -5713516480340333056L,
            -5702030608556698112L, -5690148005851018752L, -5677863184109371904L, -5665170350903313408L,
            -5652063400924580608L, -5638535907000141312L, -5624581109999480320L, -5610191908627599872L,
            -5595360848093632768L, -5580080108034218752L, -5564341489875549952L, -5548136403221394688L,
            -5531455851545399296L, -5514290416593586944L, -5496630242226406656L, -5478465016761742848L,
            -5459783954986665216L, -5440575777891777024L, -5420828692432397824L, -5400530368638773504L,
            -5379667916699401728L, -5358227861294116864L, -5336196115274292224L, -5313557951078385920L,
            -5290297970633451520L, -5266400072915222272L, -5241847420214015744L, -5216622401043726592L,
            -5190706591719534080L, -5164080714589203200L, -5136724594099067136L, -5108617109269313024L,
            -5079736143458214912L, -5050058530461741312L, -5019559997031891968L, -4988215100963582976L,
            -4955997165645491968L, -4922878208652041728L, -4888828866780320000L, -4853818314258475776L,
            -4817814175855180032L, -4780782432601701888L, -4742687321746719232L, -4703491227581444608L,
            -4663154564978699264L, -4621635653358766336L, -4578890580370785792L, -4534873055659683584L,
            -4489534251700611840L, -4442822631898829568L, -4394683764809104128L, -4345060121983362560L,
            -4293890858708922880L, -4241111576153830144L, -4186654061692619008L, -4130446006804747776L,
            -4072410698657718784L, -4012466683838401024L, -3950527400305017856L, -3886500774061896704L,
            -3820288777467837184L, -3751786943594897664L, -3680883832433527808L, -3607460442623922176L,
            -3531389562483324160L, -3452535052891361792L, -3370751053395887872L, -3285881101633968128L,
            -3197757155301365504L, -3106198503156485376L, -3011010550911937280L, -2911983463883580928L,
            -2808890647470271744L, -2701487041141149952L, -2589507199690603520L, -2472663129329160192L,
            -2350641842139870464L, -2223102583770035200L, -2089673683684728576L, -1949948966090106880L,
            -1803483646855993856L, -1649789631480328192L, -1488330106139747584L, -1318513295725618176L,
            -1139685236927327232L, -951121376596854784L, -752016768184775936L, -541474585642866432L,
            -318492605725778432L, -81947227249193216L, 169425512612864512L, 437052607232193536L, 722551297568809984L,
            1027761939299714304L, 1354787941622770432L, 1706044619203941632L, 2084319374409574144L,
            2492846399593711360L, 2935400169348532480L, 3416413484613111552L, 3941127949860576256L,
            4515787798793437952L, 5147892401439714304L, 5846529325380406016L, 6622819682216655360L,
            7490522659874166016L, 8466869998277892096L, 8216968526387345408L, 4550693915488934656L,
            7628019504138977280L, 6605080500908005888L, 7121156327650272512L, 2484871780331574272L,
            7179104797032803328L, 7066086283830045440L, 1516500120817362944L, 216305945438803456L, 6295963418525324544L,
            2889316805630113280L, -2712587580533804032L, 6562498853538167040L, 7975754821147501312L,
            -9223372036854775808L, -9223372036854775808L};
        /**
         * The precomputed ziggurat lengths, denoted X_i in the main text.
         * <ul>
         * <li>X_i = length of ziggurat layer i.
         * <li>X_j is the upper-left X coordinate of overhang j (starting from 1).
         * <li>X_(j-1) is the lower-right X coordinate of overhang j.
         * </ul>
         * <p>Values have been scaled by 2^-63.
         * Contains {@code I_MAX + 1} entries as the final value is 0.
         */
        private static final double[] X = {3.9421662825398133E-19, 3.720494500411901E-19, 3.582702448062868E-19,
            3.480747623654025E-19, 3.3990177171882136E-19, 3.330377836034014E-19, 3.270943881761755E-19,
            3.21835771324951E-19, 3.171075854184043E-19, 3.1280307407034065E-19, 3.088452065580402E-19,
            3.051765062410735E-19, 3.01752902925846E-19, 2.985398344070532E-19, 2.9550967462801797E-19,
            2.9263997988491663E-19, 2.8991225869977476E-19, 2.873110878022629E-19, 2.8482346327101335E-19,
            2.824383153519439E-19, 2.801461396472703E-19, 2.7793871261807797E-19, 2.758088692141121E-19,
            2.737503269830876E-19, 2.7175754543391047E-19, 2.6982561247538484E-19, 2.6795015188771505E-19,
            2.6612724730440033E-19, 2.6435337927976633E-19, 2.626253728202844E-19, 2.609403533522414E-19,
            2.5929570954331E-19, 2.5768906173214726E-19, 2.561182349771961E-19, 2.545812359339336E-19,
            2.530762329237246E-19, 2.51601538677984E-19, 2.501555953364619E-19, 2.487369613540316E-19,
            2.4734430003079206E-19, 2.4597636942892726E-19, 2.446320134791245E-19, 2.4331015411139206E-19,
            2.4200978427132955E-19, 2.407299617044588E-19, 2.3946980340903347E-19, 2.3822848067252674E-19,
            2.37005214619318E-19, 2.357992722074133E-19, 2.346099626206997E-19, 2.3343663401054455E-19,
            2.322786705467384E-19, 2.3113548974303765E-19, 2.300065400270424E-19, 2.2889129852797606E-19,
            2.2778926905921897E-19, 2.266999802752732E-19, 2.2562298398527416E-19, 2.245578536072726E-19,
            2.235041827493391E-19, 2.2246158390513294E-19, 2.214296872529625E-19, 2.2040813954857555E-19,
            2.19396603102976E-19, 2.183947548374962E-19, 2.1740228540916853E-19, 2.164188984001652E-19,
            2.1544430956570613E-19, 2.1447824613540345E-19, 2.1352044616350571E-19, 2.1257065792395107E-19,
            2.1162863934653125E-19, 2.1069415749082026E-19, 2.0976698805483467E-19, 2.0884691491567363E-19,
            2.0793372969963634E-19, 2.0702723137954107E-19, 2.061272258971713E-19, 2.0523352580895635E-19,
            2.0434594995315797E-19, 2.0346432313698148E-19, 2.0258847584216418E-19, 2.0171824394771313E-19,
            2.008534684685753E-19, 1.9999399530912015E-19, 1.9913967503040585E-19, 1.9829036263028144E-19,
            1.9744591733545175E-19, 1.9660620240469857E-19, 1.9577108494251485E-19, 1.9494043572246307E-19,
            1.941141290196216E-19, 1.9329204245152935E-19, 1.9247405682708168E-19, 1.9166005600287074E-19,
            1.9084992674649826E-19, 1.900435586064234E-19, 1.8924084378793725E-19, 1.8844167703488436E-19,
            1.8764595551677749E-19, 1.868535787209745E-19, 1.8606444834960934E-19, 1.8527846822098793E-19,
            1.8449554417517928E-19, 1.8371558398354868E-19, 1.8293849726199566E-19, 1.8216419538767393E-19,
            1.8139259141898448E-19, 1.8062360001864453E-19, 1.7985713737964743E-19, 1.7909312115393845E-19,
            1.78331470383642E-19, 1.7757210543468428E-19, 1.7681494793266395E-19, 1.760599207008314E-19,
            1.753069477000441E-19, 1.7455595397057217E-19, 1.7380686557563475E-19, 1.7305960954655264E-19,
            1.7231411382940904E-19, 1.7157030723311378E-19, 1.7082811937877138E-19, 1.7008748065025788E-19,
            1.6934832214591352E-19, 1.686105756312635E-19, 1.6787417349268046E-19, 1.6713904869190636E-19,
            1.6640513472135291E-19, 1.6567236556010242E-19, 1.6494067563053266E-19, 1.6420999975549115E-19,
            1.6348027311594532E-19, 1.627514312090366E-19, 1.6202340980646725E-19, 1.6129614491314931E-19,
            1.605695727260459E-19, 1.598436295931348E-19, 1.591182519724249E-19, 1.5839337639095554E-19,
            1.57668939403708E-19, 1.569448775523589E-19, 1.562211273238026E-19, 1.554976251083707E-19,
            1.547743071576727E-19, 1.540511095419833E-19, 1.5332796810709688E-19, 1.5260481843056974E-19,
            1.5188159577726683E-19, 1.5115823505412761E-19, 1.5043467076406199E-19, 1.4971083695888395E-19,
            1.4898666719118714E-19, 1.4826209446506113E-19, 1.4753705118554365E-19, 1.468114691066983E-19,
            1.4608527927820112E-19, 1.453584119903145E-19, 1.4463079671711862E-19, 1.4390236205786415E-19,
            1.4317303567630177E-19, 1.4244274423783481E-19, 1.4171141334433217E-19, 1.4097896746642792E-19,
            1.4024532987312287E-19, 1.3951042255849034E-19, 1.3877416616527576E-19, 1.3803647990516385E-19,
            1.3729728147547174E-19, 1.3655648697200824E-19, 1.3581401079782068E-19, 1.35069765567529E-19,
            1.3432366200692418E-19, 1.3357560884748263E-19, 1.3282551271542047E-19, 1.3207327801488087E-19,
            1.3131880680481524E-19, 1.3056199866908076E-19, 1.2980275057923788E-19, 1.2904095674948608E-19,
            1.2827650848312727E-19, 1.2750929400989213E-19, 1.2673919831340482E-19, 1.2596610294799512E-19,
            1.2518988584399374E-19, 1.2441042110056523E-19, 1.2362757876504165E-19, 1.2284122459762072E-19,
            1.2205121982017852E-19, 1.2125742084782245E-19, 1.2045967900166973E-19, 1.196578402011802E-19,
            1.1885174463419555E-19, 1.180412264026409E-19, 1.1722611314162064E-19, 1.164062256093911E-19,
            1.1558137724540874E-19, 1.1475137369333185E-19, 1.1391601228549047E-19, 1.1307508148492592E-19,
            1.1222836028063025E-19, 1.1137561753107903E-19, 1.1051661125053526E-19, 1.0965108783189755E-19,
            1.0877878119905372E-19, 1.0789941188076655E-19, 1.070126859970364E-19, 1.0611829414763286E-19,
            1.0521591019102928E-19, 1.0430518990027552E-19, 1.0338576948035472E-19, 1.0245726392923699E-19,
            1.015192652220931E-19, 1.0057134029488235E-19, 9.961302879967281E-20, 9.864384059945991E-20,
            9.766325296475582E-20, 9.667070742762345E-20, 9.566560624086667E-20, 9.464730838043321E-20,
            9.361512501732351E-20, 9.256831437088728E-20, 9.150607583763877E-20, 9.042754326772572E-20,
            8.933177723376368E-20, 8.821775610232788E-20, 8.708436567489232E-20, 8.593038710961216E-20,
            8.475448276424435E-20, 8.355517950846234E-20, 8.233084893358536E-20, 8.107968372912985E-20,
            7.979966928413386E-20, 7.848854928607274E-20, 7.714378370093469E-20, 7.576249697946757E-20,
            7.434141357848533E-20, 7.287677680737843E-20, 7.136424544352537E-20, 6.979876024076107E-20,
            6.817436894479905E-20, 6.648399298619854E-20, 6.471911034516277E-20, 6.28693148131037E-20,
            6.092168754828126E-20, 5.885987357557682E-20, 5.666267511609098E-20, 5.430181363089457E-20,
            5.173817174449422E-20, 4.8915031722398545E-20, 4.57447418907553E-20, 4.2078802568583416E-20,
            3.762598672240476E-20, 3.162858980588188E-20, 0.0};
        /**
         * The precomputed ziggurat heights, denoted Y_i in the main text.
         * <ul>
         * <li>Y_i = height of ziggurat layer i.
         * <li>Y_j is the upper-left Y coordinate of overhang j (starting from 1).
         * <li>Y_(j-1) is the lower-right Y coordinate of overhang j.
         * </ul>
         * <p>Values have been scaled by 2^-63.
         * Contains {@code I_MAX + 1} entries as the final value is pdf(x=0).
         */
        private static final double[] Y = {1.4598410796619063E-22, 3.0066613427942797E-22, 4.612972881510347E-22,
            6.266335004923436E-22, 7.959452476188154E-22, 9.687465502170504E-22, 1.144687700237944E-21,
            1.3235036304379167E-21, 1.504985769205313E-21, 1.6889653000719298E-21, 1.8753025382711626E-21,
            2.063879842369519E-21, 2.2545966913644708E-21, 2.44736615188018E-21, 2.6421122727763533E-21,
            2.8387681187879908E-21, 3.0372742567457284E-21, 3.237577569998659E-21, 3.439630315794878E-21,
            3.64338936579978E-21, 3.848815586891231E-21, 4.0558733309492775E-21, 4.264530010428359E-21,
            4.474755742230507E-21, 4.686523046535558E-21, 4.899806590277526E-21, 5.114582967210549E-21,
            5.330830508204617E-21, 5.548529116703176E-21, 5.767660125269048E-21, 5.988206169917846E-21,
            6.210151079544222E-21, 6.433479778225721E-21, 6.65817819857139E-21, 6.884233204589318E-21,
            7.11163252279571E-21, 7.340364680490309E-21, 7.570418950288642E-21, 7.801785300137974E-21,
            8.034454348157002E-21, 8.268417321733312E-21, 8.503666020391502E-21, 8.740192782010952E-21,
            8.97799045202819E-21, 9.217052355306144E-21, 9.457372270392882E-21, 9.698944405926943E-21,
            9.941763378975842E-21, 1.0185824195119818E-20, 1.043112223011477E-20, 1.0677653212987396E-20,
            1.0925413210432004E-20, 1.1174398612392891E-20, 1.1424606118728715E-20, 1.1676032726866302E-20,
            1.1928675720361027E-20, 1.2182532658289373E-20, 1.2437601365406785E-20, 1.2693879923010674E-20,
            1.2951366660454145E-20, 1.321006014726146E-20, 1.3469959185800733E-20, 1.3731062804473644E-20,
            1.3993370251385596E-20, 1.4256880988463136E-20, 1.452159468598837E-20, 1.4787511217522902E-20,
            1.505463065519617E-20, 1.5322953265335218E-20, 1.5592479504415048E-20, 1.5863210015310328E-20,
            1.6135145623830982E-20, 1.6408287335525592E-20, 1.6682636332737932E-20, 1.6958193971903124E-20,
            1.7234961781071113E-20, 1.7512941457646084E-20, 1.7792134866331487E-20, 1.807254403727107E-20,
            1.8354171164377277E-20, 1.8637018603838945E-20, 1.8921088872801004E-20, 1.9206384648209468E-20,
            1.9492908765815636E-20, 1.9780664219333857E-20, 2.006965415974784E-20, 2.035988189476086E-20,
            2.0651350888385696E-20, 2.094406476067054E-20, 2.1238027287557466E-20, 2.1533242400870487E-20,
            2.1829714188430474E-20, 2.2127446894294597E-20, 2.242644491911827E-20, 2.2726712820637798E-20,
            2.3028255314272276E-20, 2.3331077273843558E-20, 2.3635183732413286E-20, 2.3940579883236352E-20,
            2.4247271080830277E-20, 2.455526284216033E-20, 2.4864560847940368E-20, 2.5175170944049622E-20,
            2.548709914306593E-20, 2.5800351625915997E-20, 2.6114934743643687E-20, 2.6430855019297323E-20,
            2.674811914993741E-20, 2.7066734008766247E-20, 2.7386706647381193E-20, 2.770804429815356E-20,
            2.803075437673527E-20, 2.835484448469575E-20, 2.868032241229163E-20, 2.9007196141372126E-20,
            2.933547384842322E-20, 2.966516390775399E-20, 2.9996274894828624E-20, 3.0328815589748056E-20,
            3.066279498088529E-20, 3.099822226867876E-20, 3.133510686958861E-20, 3.167345842022056E-20,
            3.201328678162299E-20, 3.235460204376261E-20, 3.2697414530184806E-20, 3.304173480286495E-20,
            3.338757366725735E-20, 3.373494217754894E-20, 3.408385164212521E-20, 3.443431362925624E-20,
            3.4786339973011376E-20, 3.5139942779411164E-20, 3.549513443282617E-20, 3.585192760263246E-20,
            3.621033525013417E-20, 3.6570370635764384E-20, 3.693204732657588E-20, 3.729537920403425E-20,
            3.76603804721264E-20, 3.8027065665798284E-20, 3.839544965973665E-20, 3.876554767751017E-20,
            3.9137375301086406E-20, 3.951094848074217E-20, 3.988628354538543E-20, 4.0263397213308566E-20,
            4.064230660339354E-20, 4.1023029246790967E-20, 4.140558309909644E-20, 4.178998655304882E-20,
            4.217625845177682E-20, 4.256441810262176E-20, 4.29544852915662E-20, 4.334648029830012E-20,
            4.3740423911958146E-20, 4.4136337447563716E-20, 4.4534242763218286E-20, 4.4934162278076256E-20,
            4.5336118991149025E-20, 4.5740136500984466E-20, 4.614623902627128E-20, 4.655445142742113E-20,
            4.696479922918509E-20, 4.737730864436494E-20, 4.779200659868417E-20, 4.820892075688811E-20,
            4.8628079550147814E-20, 4.9049512204847653E-20, 4.9473248772842596E-20, 4.9899320163277674E-20,
            5.032775817606897E-20, 5.0758595537153414E-20, 5.1191865935622696E-20, 5.162760406286606E-20,
            5.2065845653856416E-20, 5.2506627530725194E-20, 5.294998764878345E-20, 5.3395965145159426E-20,
            5.3844600390237576E-20, 5.429593504209936E-20, 5.475001210418387E-20, 5.520687598640507E-20,
            5.566657256998382E-20, 5.612914927627579E-20, 5.659465513990248E-20, 5.706314088652056E-20,
            5.753465901559692E-20, 5.800926388859122E-20, 5.848701182298758E-20, 5.89679611926598E-20,
            5.945217253510347E-20, 5.99397086661226E-20, 6.043063480261893E-20, 6.092501869420053E-20,
            6.142293076440286E-20, 6.192444426240153E-20, 6.242963542619394E-20, 6.293858365833621E-20,
            6.345137171544756E-20, 6.396808591283496E-20, 6.448881634575274E-20, 6.501365712899535E-20,
            6.554270665673171E-20, 6.607606788473072E-20, 6.66138486374042E-20, 6.715616194241298E-20,
            6.770312639595058E-20, 6.825486656224641E-20, 6.881151341132782E-20, 6.937320479965968E-20,
            6.994008599895911E-20, 7.05123102792795E-20, 7.109003955339717E-20, 7.16734450906448E-20,
            7.226270830965578E-20, 7.285802166105734E-20, 7.34595896130358E-20, 7.406762975496755E-20,
            7.468237403705282E-20, 7.530407016722667E-20, 7.593298319069855E-20, 7.656939728248375E-20,
            7.721361778948768E-20, 7.786597356641702E-20, 7.852681965945675E-20, 7.919654040385056E-20,
            7.987555301703797E-20, 8.056431178890163E-20, 8.126331299642618E-20, 8.19731007037063E-20,
            8.269427365263403E-20, 8.342749350883679E-20, 8.417349480745342E-20, 8.493309705283207E-20,
            8.57072195782309E-20, 8.64968999859307E-20, 8.730331729565533E-20, 8.81278213788595E-20,
            8.897197092819667E-20, 8.983758323931406E-20, 9.072680069786954E-20, 9.164218148406354E-20,
            9.258682640670276E-20, 9.356456148027886E-20, 9.458021001263618E-20, 9.564001555085036E-20,
            9.675233477050313E-20, 9.792885169780883E-20, 9.918690585753133E-20, 1.0055456271343397E-19,
            1.0208407377305566E-19, 1.0390360993240711E-19, 1.0842021724855044E-19};

        /** Exponential sampler used for the long tail. */
        private final SharedStateContinuousSampler exponential;

        /**
         * @param rng Generator of uniformly distributed random numbers.
         */
        private NormalizedGaussian(UniformRandomProvider rng) {
            super(rng);
            exponential = ZigguratSampler.Exponential.of(rng);
        }

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return toString("normalized Gaussian");
        }

        /** {@inheritDoc} */
        @Override
        public double sample() {
            // Ideally this method byte code size should be below -XX:MaxInlineSize
            // (which defaults to 35 bytes). This compiles to 33 bytes.
            final long xx = nextLong();
            // Float multiplication squashes these last 8 bits, so they can be used to sample i
            final int i = ((int) xx) & MASK_INT8;

            if (i < I_MAX) {
                // Early exit.
                // Expected frequency = 0.988281
                return X[i] * xx;
            }

            return edgeSample(xx);
        }

        /**
         * Create the sample from the edge of the ziggurat.
         *
         * <p>This method has been extracted to fit the main sample method within 35 bytes (the
         * default size for a JVM to inline a method).
         *
         * @param xx Initial random deviate
         * @return a sample
         */
        private double edgeSample(long xx) {
            // Expected frequency = 0.0117188

            // Drop the sign bit to create u:
            long u1 = xx & MAX_INT64;
            // Extract the sign bit for use later
            // Use 2 - 1 or 0 - 1
            final double signBit = ((xx >>> 62) & 0x2) - 1.0;
            final int j = selectRegion();
            // Four kinds of overhangs:
            //  j = 0                :  Sample from tail
            //  0 < j < J_INFLECTION :  Overhang is concave; only sample from Lower-Left triangle
            //  j = J_INFLECTION     :  Must sample from entire overhang rectangle
            //  j > J_INFLECTION     :  Overhangs are convex; implicitly accept point in Lower-Left triangle
            //
            // Conditional statements are arranged such that the more likely outcomes are first.
            double x;
            if (j > J_INFLECTION) {
                // Convex overhang
                // Expected frequency: 0.00892899
                // Observed loop repeat frequency: 0.389804
                for (;;) {
                    x = interpolate(X, j, u1);
                    // u2 = u1 + (u2 - u1) = u1 + uDistance
                    final long uDistance = randomInt63() - u1;
                    if (uDistance >= 0) {
                        // Lower-left triangle
                        break;
                    }
                    if (uDistance >= CONVEX_E_MAX &&
                        // Within maximum distance of f(x) from the triangle hypotenuse.
                        // Frequency (per upper-right triangle): 0.431497
                        // Reject frequency: 0.489630
                        interpolate(Y, j, u1 + uDistance) < Math.exp(-0.5 * x * x)) {
                        break;
                    }
                    // uDistance < MAX_IE (upper-right triangle) or rejected as above the curve
                    u1 = randomInt63();
                }
            } else if (j < J_INFLECTION) {
                if (j == 0) {
                    // Tail
                    // Expected frequency: 0.000276902
                    // Note: Although less frequent than the next branch, j == 0 is a subset of
                    // j < J_INFLECTION and must be first.
                    // Observed loop repeat frequency: 0.0634786
                    do {
                        x = ONE_OVER_X_0 * exponential.sample();
                    } while (exponential.sample() < 0.5 * x * x);
                    x += X_0;
                } else {
                    // Concave overhang
                    // Expected frequency: 0.00249694
                    // Observed loop repeat frequency: 0.0123784
                    for (;;) {
                        // u2 = u1 + (u2 - u1) = u1 + uDistance
                        long uDistance = randomInt63() - u1;
                        if (uDistance < 0) {
                            // Upper-right triangle. Reflect in hypotenuse.
                            uDistance = -uDistance;
                            // Update u1 to be min(u1, u2) by subtracting the distance between them
                            u1 -= uDistance;
                        }
                        x = interpolate(X, j, u1);
                        if (uDistance > CONCAVE_E_MAX ||
                            interpolate(Y, j, u1 + uDistance) < Math.exp(-0.5 * x * x)) {
                            break;
                        }
                        u1 = randomInt63();
                    }
                }
            } else {
                // Inflection point
                // Expected frequency: 0.000015914
                // Observed loop repeat frequency: 0.500213
                for (;;) {
                    x = interpolate(X, j, u1);
                    if (interpolate(Y, j, randomInt63()) < Math.exp(-0.5 * x * x)) {
                        break;
                    }
                    u1 = randomInt63();
                }
            }
            return signBit * x;
        }

        /**
         * Select the overhang region or the tail using alias sampling.
         *
         * @return the region
         */
        private int selectRegion() {
            final long x = nextLong();
            // j in [0, 256)
            final int j = ((int) x) & MASK_INT8;
            // map to j in [0, N] with N the number of layers of the ziggurat
            return x >= IPMF[j] ? MAP[j] & MASK_INT8 : j;
        }

        /** {@inheritDoc} */
        @Override
        public NormalizedGaussian withUniformRandomProvider(UniformRandomProvider rng) {
            return new NormalizedGaussian(rng);
        }

        /**
         * Create a new normalised Gaussian sampler.
         *
         * @param rng Generator of uniformly distributed random numbers.
         * @return the sampler
         */
        public static NormalizedGaussian of(UniformRandomProvider rng) {
            return new NormalizedGaussian(rng);
        }
    }

    /**
     * @param rng Generator of uniformly distributed random numbers.
     */
    ZigguratSampler(UniformRandomProvider rng) {
        this.rng = rng;
    }

    /**
     * Generate a string to represent the sampler.
     *
     * @param type Sampler type (e.g. "exponential").
     * @return the string
     */
    String toString(String type) {
        return "Modified ziggurat " + type + " deviate [" + rng.toString() + "]";
    }

    /**
     * Generates a {@code long}.
     *
     * @return the long
     */
    long nextLong() {
        return rng.nextLong();
    }

    /**
     * Generates a positive {@code long} in {@code [0, 2^63)}.
     *
     * <p>In the c reference implementation RANDOM_INT63() obtains the current random value
     * and then advances the RNG. This implementation obtains a new value from the RNG.
     * Thus the java implementation must ensure a previous call to the RNG is cached
     * if RANDOM_INT63() is called without first advancing the RNG.
     *
     * @return the long
     */
    long randomInt63() {
        return rng.nextLong() >>> 1;
    }

    /**
     * Compute the value of a point using linear interpolation of a data table of values
     * using the provided uniform deviate.
     * <pre>
     *  value = v[j] + u * (v[j-1] - v[j])
     * </pre>
     *
     * <p>This can be used to generate the (x,y) coordinates of a point in a rectangle
     * with the upper-left corner at {@code j} and lower-right corner at {@code j-1}:
     *
     * <pre>{@code
     *    X[j],Y[j]
     *        |\ |
     *        | \|
     *        |  \
     *        |  |\    Ziggurat overhang j (with hypotenuse not pdf(x))
     *        |  | \
     *        |  u2 \
     *        |      \
     *        |-->u1  \
     *        +-------- X[j-1],Y[j-1]
     *
     *   x = X[j] + u1 * (X[j-1] - X[j])
     *   y = Y[j] + u2 * (Y[j-1] - Y[j])
     * }</pre>
     *
     * @param v Ziggurat data table. Values assumed to be scaled by 2^-63.
     * @param j Index j. Value assumed to be above zero.
     * @param u Uniform deviate. Value assumed to be in {@code [0, 2^63)}.
     * @return value
     */
    static double interpolate(double[] v, int j, long u) {
        // Note:
        // The reference code used two methods to interpolate X and Y separately.
        // The c language exploited declared pointers to X and Y and used a #define construct.
        // This computed X identically to this method but Y as:
        // y = Y[j-1] + (1-u2) * (Y[j] - Y[j-1])
        // Using a single method here clarifies the code. It avoids generating (1-u).
        // Tests show the alternative is 1 ULP different with approximately 3% frequency.
        // It has not been measured more than 1 ULP different.
        return v[j] * TWO_POW_63 + u * (v[j - 1] - v[j]);
    }

    /**
     * Helper function to convert {@code int} values to bytes using a narrowing primitive conversion.
     *
     * <pre>
     * int i = ...
     * byte b = (byte) i;
     * </pre>
     *
     * @param values Integer values.
     * @return the bytes
     */
    static byte[] toBytes(int[] values) {
        final byte[] bytes = new byte[values.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }
}
