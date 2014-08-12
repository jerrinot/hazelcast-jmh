#!/bin/sh
#mvn clean install
#java -jar target/microbenchmarks.jar -tu s -f 2 -wi 1 -i 4 -rf json -rff results.json -bm thrpt ".*MultiNodeMapBenchmark.*" -prof stack

#total capacity of SLAB buffers
capacity=180g

#no of SLAB segments
noOfSegments=100

#number of iterations per a single @benchmark method invocation. Each iteration will add a unique entry.
operationsPerInvocation=48643200

Xmx=230g
Xms=230g

PROPERTIES="-DnoOfSegments=${noOfSegments} -Dcapacity=${capacity}"
JVM_OPTS="-Xmx${Xmx} -Xms${Xms}"

echo Fork Properties: $PROPERTIES
echo JVM Options: $JVM_OPTS

java $PROPERTIES -jar microbenchmarks.jar -tu s -f 2 -wi 2 -i 10 -jvmArgsPrepend "$JVM_OPTS" -opi $operationsPerInvocation -bm thrpt ".*OnheapSlabBenchmark.*"
