<!---
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

Apache Commons RNG Stress Test Example
======================================

The stress test module contains an application for calling external tools that perform stringent
uniformity tests. The following shows an example of how to run **Dieharder** and **TestU01**.

Installation on Linux/MacOS
---------------------------

This assumes that the source code has been checked out and the current working directory is
`commons-rng-examples/examples-stress`.

### TestU01

This can be installed from [TestU01](http://simul.iro.umontreal.ca/testu01/tu01.html) or using
the available packages (linux):

        > apt-get install libtestu01 libtestu01-dev testu01-bin

If installing **TestU01** from source then it is recommended to do this using an install directory
to avoid header clashes as the install puts headers directly into `<install directory>/include`:

        > ./configure --prefix=/usr/local/testu01
        > make
        > make install

A simple bridge is provided to read the integers from `stdin` and pass them to the **TestU01** library.
This can be compiled using one of the following (depending on the library names):

         > gcc src/main/c/stdin2testu01.c -o stdin2testu01 -ltestu01 -lprobdist -lmylib -lm
         > gcc src/main/c/stdin2testu01.c -o stdin2testu01 -ltestu01 \
               -ltestu01probdist -ltestu01mylib -lm

Note: If **TestU01** was installed from source then the `stdin2testu01.c` may have to be modified to
use different headers. Open the source and update to:

`#define TEST_U01_SRC 1`

Then compile with the `<install directory>/include` and `lib` directories:

        > gcc src/main/c/stdin2testu01.c -o stdin2testu01 \
              -I/usr/local/testu01/include \
              -L/usr/local/testu01/lib \
              -ltestu01 -lprobdist -lmylib -lm

### Dieharder

This can be installed from [Dieharder](http://webhome.phy.duke.edu/~rgb/General/dieharder.php) or
using the available packages:

        > apt-get install dieharder
        > brew install dieharder

The `dieharder` executable can read integers from `stdin` for analysis so no additional steps are
required.

Test platform Endianness
------------------------

The stress test application will output raw binary data for generated integers or longs. 
An integer is 4 bytes and a long is 8 bytes and thus the byte order or [Endianness](https://en.wikipedia.org/wiki/Endianness) of the data
must be correct for the test application. The stress test application can support either big-endian
or little-endian format. The application will auto-detect the platform and will default to output
binary data using the native byte order.

The **Dieharder** application and `stdin2testu01` bridge application for the **TestU01**
test suites read binary data using a c-library function that requires the data to be in the native
byte order. These test suites will be supported automatically.

If using a alternative test suite the endian format expected by the test suite must be verified.
More details of how to do this are provided in the [endianness](./endianness.md) page.

Running on Linux/MacOS
----------------------

Build the `examples-stress` jar file:

        > mvn package -P examples-stress

This will create a single jar file with all the dependencies in the `target` directory.

The jar file is executable and provides usage information with the `--help` or `-h` option:

        > java -jar target/examples-stress.jar -h

The list of generators that can be tested can be customised. The default is to use all known
generators that do not require arguments in addition to a random seed. To create a template list
use the `list` command:

        > java -jar target/examples-stress.jar list

        # ID   RandomSource           trials   [constructor arguments ...]
        1      JDK                    1
        2      WELL_512_A             1
        ...

Arguments can be specified for those generators that require them. Note that the identifier for
each line, corresponding to a tested generator, must be unique. The identifier will be used to name
the output results file for the generator.

To run the stress test use the `stress` command and provide the following arguments:

| Argument  | Description | Example |
| --------- | ----------- | ------- |
| executable | The test tool | dieharder, stdin2testu01 |
| ... | Arguments for the test tool | dieharder: -a -g 200 -Y 1 -k 2 <br/> stdin2testu01: SmallCrush, Crush, BigCrush |

The command sets defaults for other options that can be changed, for example the output results
file prefix, the number of concurrent tasks, byte-order or the number of trials per generator.
Use the `--help` option to show the available options.

### TestU01

        > java -jar target/examples-stress.jar stress \
              --prefix target/tu_ \
              ./stdin2testu01 \
              BigCrush

### Dieharder

        > java -jar target/examples-stress.jar stress \
              --prefix target/dh_ \
              /usr/bin/dieharder \
              -a -g 200 -Y 1 -k 2

The output results can be viewed using the `results` command:

        > java -jar target/examples-stress.jar results \
              target/tu_* \
              target/dh_*

Various formats are available. Use the `--help` option to show the available options.

Test 64-bit generators
----------------------

The available random generators output either 32-bits or 64-bits per cycle. The test applications
**Dieharder** and **TestU01** require 32-bit input. The standard method for a 64-bit generator is
to use the upper and then lower 32-bits of each 64-bit output. The stress test application has
options to use only the upper or the lower 32-bits for testing. These can then be bit-reversed or
byte-reversed if desired.

The `list` command can output available generators by provider type. For example to output the
64-bit providers to a `rng64.list` file:

        > java -jar target/examples-stress.jar list \
              --provider long > rng64.list

The list can then be used to test 64-bit providers, for example to test the lower 32-bits in reverse
order using **BigCrush**:

        > java -jar target/examples-stress.jar stress \
              --list rng64.list \
              --low-bits \
              --reverse-bits \
              --prefix target/tu_lo_r_ \
              ./stdin2testu01 \
              BigCrush

If a 32-bit provider is used with the `--low-bits` or `--upper-bits` options then an error
message is shown.
