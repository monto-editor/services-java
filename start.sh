java -jar dist/services-java.jar \
    -t -p -o -c \
    -address tcp://* \
    -registration tcp://*:5004 \
    -configuration tcp://*:5007 \
    -dyndeps tcp://*:5009 \
    -resources 5050
