#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

eclipse_jar="$DIR/dist/services-java.jar"
gradle_jar="$DIR/build/libs/services-java.jar"

if [ -f "$eclipse_jar" ]; then
    jar="$eclipse_jar"
elif [ -f "$gradle_jar" ]; then
    jar="$gradle_jar"
else
    printf "No jar found. Please build the project first.\n" >&2
    exit 99
fi

java -jar "$jar" \
     -highlighting \
     -javaccparser \
     -outline \
     -codecompletion \
     -address tcp://* \
     -registration tcp://*:5004 \
     -configuration tcp://*:5007 \
     -dyndeps tcp://*:5009 \
     -resources 5050
