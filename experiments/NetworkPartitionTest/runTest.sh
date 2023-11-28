#!/bin/bash


function getLatestLeaderElectedTime() {
    # arg 1 is host ip, arg 2 is host id
    BASE_PATH="/home/ec2-user/Projects/baseImplementation/experiments"
    LOG_FILE_NAME="raft-debugging.log"
    RAW_OUTPUT=$(ssh -f "ec2-user@$1" "cat $BASE_PATH/server$2/$LOG_FILE_NAME")
    ELECT_TIME=$(echo $RAW_OUTPUT | grep -Eo "leader at timestamp: [0-9]+" | grep -Eo "[0-9]+" | tail -n1)
    if [ -z "$ELECT_TIME" ]; then
        echo "0"
    else
        echo "$ELECT_TIME"
    fi
}

NUM_TESTS=10
HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

for HOST_FILE in "${HOST_FILES[@]}"; do 
    echo "STARTING TESTS WITH HOST FILE $HOST_FILE"
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}"

    TEST_TIMES=()

    for TEST_NUM in $(seq 1 $NUM_TESTS); do
        printf "\tSTART OF TEST $TEST_NUM\n"
        # create a new cluster
        ../runRemote.sh --file "$HOST_FILE" --cluster "../cluster${NUM_HOSTS}.json" > /dev/null 2>&1

        sleep 3

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

        printf "\t\tCurrent leader is: $LEADER_ID\n"

        
        # LEADER HAS BEEN ELECTED START THE PARTITION
        ../partition/runRemoteBlockServers.sh --hostsFile "$HOST_FILE" --partitionFile "../partition/partition${NUM_HOSTS}.txt" > /dev/null 2>&1

        # wait 6 seconds so that all servers timeout 
        sleep 6


        HEAL_TIME=$(date +%s%N)
        ../partition/runRemoteHealPartition.sh --hostsFile "$HOST_FILE" > /dev/null 2>&1
        printf "\t\tHEAL_TIME: $HEAL_TIME\n"

        # TODO make sleep longer for more hosts????
        sleep $(($NUM_HOSTS * 2))

        # loop over each host, find the latest time it became leader, 0 if never became leader
        # find host with greatest leader elected time 
        
        LATEST_ELECT="$HEAL_TIME"
        for ((HOST_ID=1; HOST_ID<=$NUM_HOSTS; HOST_ID++)); do
            HOST_INDEX=$(($HOST_ID - 1))
            HOST="${HOSTS[$HOST_INDEX]}"
            #printf "\tFinding latest election time for host: $HOST\n" 
            ELECT_TIME=$(getLatestLeaderElectedTime "$HOST" "$HOST_ID")
            printf "\tTime was $ELECT_TIME\n"

            LATEST_ELECT=$(( ELECT_TIME > LATEST_ELECT ? ELECT_TIME : LATEST_ELECT))
        done

        printf "\t\tlatest elect: $LATEST_ELECT\n"

        # calculate difference between timestamps and convert to milliseconds
        if [ "$LATEST_ELECT" -gt "$HEAL_TIME" ]; then 
            DIFF=$(bc <<< "$LATEST_ELECT - $HEAL_TIME")
            MS=$(bc <<< "$DIFF / 1000000")
            printf "\tMS: $MS\n"
            TEST_TIMES+=("$MS")
        fi


        # kill off cluster
        ../runRemoteKillServer.sh --file "$HOST_FILE" > /dev/null 2>&1
        sleep 2

        printf "\tEND OF TEST $TEST_NUM\n"
    done

    TOTAL="0"
    for (( TEST_NUM=0; TEST_NUM<$NUM_TESTS; TEST_NUM++ ))
    do
        TOTAL=$(bc <<< "${TEST_TIMES[$TEST_NUM]} + $TOTAL")
    done

    AVG=$(bc <<< "$TOTAL / $NUM_TESTS")

    echo "AVG time to elect leader after partition for $NUM_HOSTS hosts: $AVG"

done