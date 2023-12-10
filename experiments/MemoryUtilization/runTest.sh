#!/bin/bash

RAPID_CLASSES=("com.vrg." "io.netty." "com.google.protobuf.*" "com.google.common.*" "net.openhft.*" "io.grpc.*" "io.opencensus.*" "net.data.technology.jraft.PingPongFailureDetector")

NUM_SAMPLES=50
SAMPLE_INTERVAL=1

function avg() {
    ARRAY=("$@")
    ARRAY_LEN="${#ARRAY[@]}"

    SUM=0
    for NUM in "${ARRAY[@]}"; do
        SUM=$(($SUM + $NUM))
    done

    AVG=$(($SUM / $ARRAY_LEN))

    echo "$AVG"
}

HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

for HOST_FILE in "${HOST_FILES[@]}"; do 
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}" 

    echo "Starting for $NUM_HOSTS hosts"   

    # create a new cluster
    ../runRemote.sh --file "$HOST_FILE" > /dev/null 2>&1

    sleep 5

    HEAP_USAGE=()
    TOTAL_HISTO_MEMS=()
    RAPID_MEMS=()

    for ((ROUND=1; ROUND<=$NUM_SAMPLES; ROUND++)); do 
        printf "\tRound $ROUND\n"
        for HOST in "${HOSTS[@]}"; do
            PID=$(ssh -f "ec2-user@$HOST" "ps -u ec2-user" | grep "java" | grep -Eo "^\s*[0-9]+" | grep -Eo "[0-9]+")

            HEAP=$(ssh -f "ec2-user@$HOST" "jhsdb jmap --heap --pid $PID" | grep "used" | head -n1 | grep -Eo "\s[0-9]+\s" | grep -Eo "[0-9]+")
            HEAP_USAGE+=("$HEAP")

            #printf "\tHEAP: \t\t\t$HEAP\n"

            HISTOGRAM=$(ssh -f "ec2-user@$HOST" "jmap -histo:live $PID")

            TOTAL_HISTOGRAM_MEM=$(  echo "$HISTOGRAM" | 
                                    grep -Eo "\s+[0-9]+\s+[0-9]+\s+" | 
                                    grep -Eo "[0-9]+\s+$" |
                                    awk '{s+=$1} END {print s}'
                                )

            RAPID_MEM=0
            for RAPID_CLASS in "${RAPID_CLASSES[@]}"; do
                RES=$(  echo "$HISTOGRAM" | 
                        grep "$RAPID_CLASS" | 
                        grep -Eo "\s+[0-9]+\s+[0-9]+\s+" | 
                        grep -Eo "[0-9]+\s+$" |
                        awk '{s+=$1} END {print s}'
                    )
                RAPID_MEM=$(($RAPID_MEM + $RES))
            done

            TOTAL_HISTO_MEMS+=($TOTAL_HISTOGRAM_MEM)
            RAPID_MEMS+=($RAPID_MEM)

            #printf "\tTOTAL_HISTO_MEM: \t$TOTAL_HISTOGRAM_MEM\n"
            #printf "\tRAPID_MEM: \t\t$RAPID_MEM\n"
        done

        sleep "$SAMPLE_INTERVAL"
    done

    AVG_HEAP=$(avg "${HEAP_USAGE[@]}")
    AVG_HISTO_MEMS=$(avg "${TOTAL_HISTO_MEMS[@]}")
    AVG_MEMS=$(avg "${RAPID_MEMS[@]}")

    printf "Results for $NUM_HOSTS hosts\n"
    printf "AVG_HEAP: \t\t$AVG_HEAP\n"
    printf "AVG_HISTO_MEMS: $AVG_HISTO_MEMS\n"
    printf "AVG_MEMS: \t\t$AVG_MEMS\n"

    # kill off cluster
    ../runRemoteKillServer.sh --file "$HOST_FILE" > /dev/null 2>&1
    sleep 2

done

