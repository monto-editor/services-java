$JAVA_HOME/bin/ojava -jar dist/services-java.jar \
    -t -p -o -c \
    -address tcp://* \
    -registration tcp://*:5004
