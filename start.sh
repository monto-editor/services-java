$JAVA_HOME/bin/java -jar dist/services-java.jar \
    -t -p -o -c \
    -address tcp://* \
    -registration tcp://*:5004
