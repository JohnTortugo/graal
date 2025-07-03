#!/bin/bash

pushd ../../tests
./build.sh
popd

cp ../../tests/*.class .

SHOULD_DEBUG=""

mx  ${SHOULD_DEBUG}                                     \
    --dy /graal-js                                      \
    vm                                                  \
    @module-path.txt                                    \
    -XX:+UseG1GC                                        \
    -Xms2g                                              \
    -Xmx2g                                              \
    -XX:ReservedCodeCacheSize=30m                       \
    -Dpolyglot.engine.TraceTransferToInterpreter=true   \
    -Dpolyglot.engine.WarnInterpreterOnly=false         \
    --enable-native-access=org.graalvm.truffle          \
    --sun-misc-unsafe-memory-access=allow               \
    SimpleCompilation                                   | tee debug.txt
