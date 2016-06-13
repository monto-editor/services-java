#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

gradle_shadow_jar="$DIR/main/build/libs/main-all.jar"

if [ ! -f "$gradle_shadow_jar" ]; then
    printf "No jar found. Please build the project first.\n" >&2
    exit 99
fi

java -jar "$jar" \
     -highlighting \
     -javaccparser \
     -outline \
     -codecompletion \
     -address tcp://* \
     -registration tcp://*:5002 \
     -resources 5050
