#!/bin/bash

HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

# monitor for a minute
REFRESH_RATE=1
NUM_UPDATES=60

function avg() {
    ARRAY=("$@")
    ARRAY_LEN="${#ARRAY[@]}"

    SUM=0
    for NUM in "${ARRAY[@]}"; do
        SUM=$(bc <<< "$SUM + $NUM")
    done

    #echo "$SUM"
    AVG=$(bc -l <<< "$SUM / $ARRAY_LEN")

    echo "$AVG"
}

for HOST_FILE in "${HOST_FILES[@]}"; do 
    echo "STARTING TEST WITH HOST FILE $HOST_FILE"
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}"

    SENT_ARRAY=()
    RECV_ARRAY=()

    # create a new cluster
    ../runRemote.sh --file "$HOST_FILE" --cluster "../cluster${NUM_HOSTS}.json" > /dev/null 2>&1

    # wait for entire cluster to startup/join
    sleep 3

    printf "\tStarting nethogs\n"
    # start nethogs on each server
    for HOST in "${HOSTS[@]}"; do
        ssh -f "ec2-user@${HOST}" "sh -c 'nohup sudo nethogs -t -c $NUM_UPDATES -d $REFRESH_RATE > nethogs.txt 2>&1 &'"
    done

    sleep $(($REFRESH_RATE * $NUM_UPDATES))

    for HOST in "${HOSTS[@]}"; do
        RAW_OUTPUT=$(ssh -f "ec2-user@${HOST}" "tr -d '\0' < nethogs.txt")
        # LINES=$(echo "$RAW_OUTPUT" | grep "java")
        
        readarray -t LINES_ARRAY < <(echo "$LINES")
        echo "lines array length: ${#LINES_ARRAY[@]}"

        for LINE in "${LINES_ARRAY[@]}"; do
            SENT_KBPS=$(echo "$LINE" | grep -Eo "/[0-9]+\s+[0-9]+\.[0-9]+" | grep -Eo "[0-9]+\.[0-9]+")
            RECV_KBPS=$(echo "$LINE" | grep -Eo "\s+[0-9]+\.[0-9]+\s*$" | grep -Eo "[0-9]+\.[0-9]+")
            # printf "\t$LINE\n"
            # printf "\t\tSent: $SENT_KBPS\n"
            # printf "\t\tRecv: $RECV_KBPS\n"

            SENT_ARRAY+=("$SENT_KBPS")
            RECV_ARRAY+=("$RECV_KBPS")
        done
    done

    AVG_SENT_KBPS=$(avg "${SENT_ARRAY[@]}")
    AVG_RECV_KBPS=$(avg "${RECV_ARRAY[@]}")

    echo "Average sent kbps: $AVG_SENT_KBPS"
    echo "Average recv kbps: $AVG_RECV_KBPS"


    # kill off cluster
    ../runRemoteKillServer.sh --file "$HOST_FILE" > /dev/null 2>&1
    sleep 2
done
