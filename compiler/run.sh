#!/bin/bash

pushd ../../tests
./build.sh
popd

cp ../../tests/*.class .

SHOULD_DEBUG=""
#MODULE_PATH="/wf/graal-recompilations/graaljs/graal-js/mxbuild/jdk25/dists/jdk17/graaljs-launcher.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk17/collections.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk11/word.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk11/nativeimage.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk17/polyglot.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk17/jline3.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk17/launcher-common.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk25/dists/jdk21/truffle-api.jar:/wf/graal-recompilations/graaljs/graal-js/mxbuild/jdk25/dists/jdk17/graaljs.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk25/dists/jdk17/truffle-xz.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk17/truffle-icu4j.jar:/wf/graal-recompilations/graal/regex/mxbuild/jdk25/dists/jdk17/tregex.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk25/dists/jdk17/truffle-runtime.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk25/dists/jdk17/jniutils.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk25/dists/jdk17/truffle-compiler.jar"
MODULE_PATH="/wf/graal-recompilations/graaljs/graal-js/mxbuild/jdk26/dists/jdk17/graaljs-launcher.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk17/collections.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk11/word.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk11/nativeimage.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk17/polyglot.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk17/jline3.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk17/launcher-common.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk21/truffle-api.jar:/wf/graal-recompilations/graaljs/graal-js/mxbuild/jdk26/dists/jdk17/graaljs.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk17/truffle-xz.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk17/truffle-icu4j.jar:/wf/graal-recompilations/graal/regex/mxbuild/jdk26/dists/jdk17/tregex.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk17/truffle-runtime.jar:/wf/graal-recompilations/graal/sdk/mxbuild/jdk26/dists/jdk17/jniutils.jar:/wf/graal-recompilations/graal/truffle/mxbuild/jdk26/dists/jdk17/truffle-compiler.jar"

mx  ${SHOULD_DEBUG}                                     \
    --dy /graal-js                                      \
    vm                                                  \
    --module-path=${MODULE_PATH}                        \
    -XX:+UseG1GC                                        \
    -Xms2g                                              \
    -Xmx2g                                              \
    -XX:ReservedCodeCacheSize=20m                       \
    -Dpolyglot.engine.TraceTransferToInterpreter=true   \
    -Dpolyglot.engine.WarnInterpreterOnly=false         \
    --enable-native-access=org.graalvm.truffle          \
    --sun-misc-unsafe-memory-access=allow               \
    SimpleCompilation                         2>&1 | tee log.txt




#    -Djdk.graal.Dump=Truffle:1                          \
