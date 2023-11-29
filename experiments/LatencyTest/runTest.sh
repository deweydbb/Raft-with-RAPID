#!/bin/bash

HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

# monitor for a minute
PING_INTERVAL=1
NUM_PINGS=60

function avg() {
    ARRAY=("$@")
    ARRAY_LEN="${#ARRAY[@]}"

    SUM=0
    for NUM in "${ARRAY[@]}"; do
        SUM=$(bc <<< "$SUM + $NUM")
    done

    AVG=$(bc -l <<< "$SUM / $ARRAY_LEN")

    echo "$AVG"
}

for HOST_FILE in "${HOST_FILES[@]}"; do 
    echo "STARTING TEST WITH HOST FILE $HOST_FILE"
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}"

    PING_ARRAY=()

    # create a new cluster
    ../runRemote.sh --file "$HOST_FILE" --cluster "../cluster${NUM_HOSTS}.json" > /dev/null 2>&1

    # wait for entire cluster to startup/join
    sleep 3

    printf "\tStarting ping\n"
    # start nethogs on each server
    for ((HOST_ID=1; HOST_ID<=$NUM_HOSTS; HOST_ID++)); do
        HOST_INDEX=$((HOST_ID - 1))
        HOST="${HOSTS[$HOST_INDEX]}"

        PING_HOST_INDEX="$HOST_ID"
        if [ "$HOST_ID" -eq "$NUM_HOSTS" ]; then
            PING_HOST_INDEX="0"
        fi

        PING_HOST="${HOSTS[$PING_HOST_ID]}"

        ssh -f "ec2-user@${HOST}" "sh -c 'nohup ping -c $NUM_PINGS -i $PING_INTERVAL $PING_HOST > ping.txt 2>&1 &'"
    done

    sleep $(($PING_INTERVAL * 2))
    sleep $(($PING_INTERVAL * $NUM_PINGS))

    for HOST in "${HOSTS[@]}"; do
        RAW_OUTPUT=$(ssh -f "ec2-user@${HOST}" "tr -d '\0' < ping.txt")
        AVG_PING=$(echo "$RAW_OUTPUT" | grep "rtt min/avg" | grep -Eo "/[0-9]+\.[0-9]+/" | grep -Eo "[0-9]+\.[0-9]+")
        printf "\tavg ping $AVG_PING\n"
        PING_ARRAY+=($AVG_PING)
    done

    AVG_LATENCY=$(avg "${PING_ARRAY[@]}")

    echo "Average latency in ms: $AVG_LATENCY"


    # kill off cluster
    ../runRemoteKillServer.sh --file "$HOST_FILE" > /dev/null 2>&1
    sleep 2
done
