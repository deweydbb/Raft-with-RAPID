#!/bin/bash

BASE_PATH="/home/ec2-user/Projects/baseImplementation/experiments"
LOG_FILE_NAME="raft-debugging.log"

NUM_TESTS=5
HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

for HOST_FILE in "${HOST_FILES[@]}"; do 
    echo "STARTING TESTS WITH HOST FILE $HOST_FILE"
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}"

    TEST_TIMES=()

    for TEST_NUM in $(seq 1 $NUM_TESTS); do
        printf "START OF TEST $TEST_NUM\n"
        # create a new cluster
        ../runRemote.sh --file "$HOST_FILE" --cluster "../cluster${NUM_HOSTS}.json" > /dev/null 2>&1

        # get array of hosts from host file
        # client will arbitrarily connect to the first server
        SEED_IP="${HOSTS[0]}"

        RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --command ./DetectFailedLeaderTest/cmd.txt)
        LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        # loop until leader is determined
        while [ "$LEADER_ID" = "-1" ]; do
            sleep .5
            RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --command ./DetectFailedLeaderTest/cmd.txt)
            LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        done 

        printf "\tCurrent leader is: $LEADER_ID\n"

        LEADER_INDEX=$(($LEADER_ID-1))
        LEADER_IP="${HOSTS[$LEADER_INDEX]}"

        # kill current leader
        KILL_TIME=$(date +%s%N)
        printf "\tKILL_TIME $KILL_TIME\n"
        ssh -f "ec2-user@${LEADER_IP}" "killall -9 java"

        # calculate seed for client that is guaranteed to be alive
        SEED_ID=1
        if [ "$SEED_ID" = "$LEADER_ID" ]; then
            SEED_ID=$(($SEED_ID+1))
        fi
        SEED_INDEX=$(($SEED_ID-1))
        SEED_IP="${HOSTS[$SEED_INDEX]}"


        RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./DetectFailedLeaderTest/cmd.txt 2> /dev/null)
        NEW_LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        printf "\tnew leader id:$NEW_LEADER_ID.\n"
        # loop until new leader is elected
        while [ "$NEW_LEADER_ID" = "-1" ] || [ "$NEW_LEADER_ID" = "$LEADER_ID" ]; do
            sleep .5
            RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./DetectFailedLeaderTest/cmd.txt 2> /dev/null)
            NEW_LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
            printf "\tnew leader id:$NEW_LEADER_ID.\n"
        done 

        printf "\tNEW LEADER ID: $NEW_LEADER_ID\n"

        # calculate new leader ip
        NEW_LEADER_INDEX=$(($NEW_LEADER_ID-1))
        NEW_LEADER_IP="${HOSTS[$NEW_LEADER_INDEX]}"

        DEBUG_CONTENTS=$(ssh -f "ec2-user@${NEW_LEADER_IP}" "cat $BASE_PATH/server${NEW_LEADER_ID}/$LOG_FILE_NAME")
        ELECT_TIME=$(echo "$DEBUG_CONTENTS" | grep -Eo "leader at timestamp: [0-9]+" | grep -Eo "[0-9]+")
        # loop until timestamp we are looking for appears in the log file
        while [ "$ELECT_TIME" = "" ]; do
            sleep .5
            DEBUG_CONTENTS=$(ssh -f "ec2-user@${NEW_LEADER_IP}" "cat $BASE_PATH/server${NEW_LEADER_ID}/$LOG_FILE_NAME")
            ELECT_TIME=$(echo "$DEBUG_CONTENTS" | grep -Eo "leader at timestamp: [0-9]+" | grep -Eo "[0-9]+" | tail -n1)
        done 

        printf "\tElect time $ELECT_TIME\n"

        # calculate difference between timestamps and convert to milliseconds
        DIFF=$(bc <<< "$ELECT_TIME - $KILL_TIME")
        MS=$(bc <<< "$DIFF / 1000000")
        printf "\tMS: $MS\n"
        TEST_TIMES+=("$MS")

        # kill off cluster
        ../runRemoteKillServer.sh --file "$HOST_FILE" > /dev/null 2>&1
        sleep 2

        printf "END OF TEST $TEST_NUM\n"
    done

    TOTAL="0"
    for (( TEST_NUM=0; TEST_NUM<$NUM_TESTS; TEST_NUM++ ))
    do
        TOTAL=$(bc <<< "${TEST_TIMES[$TEST_NUM]} + $TOTAL")
    done

    echo "TOTAL: $TOTAL"

    AVG=$(bc <<< "$TOTAL / $NUM_TESTS")

    echo "AVG for $NUM_HOSTS hosts: $AVG"

done