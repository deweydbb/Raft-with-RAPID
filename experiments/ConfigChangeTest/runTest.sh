#!/bin/bash

NUM_TESTS=5
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

        RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --command ./ConfigChangeTest/cmd.txt)
        LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        # loop until leader is determined
        while [ "$LEADER_ID" = "-1" ]; do
            sleep .5
            RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --command ./ConfigChangeTest/cmd.txt)
            LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        done 

        printf "\t\tCurrent leader is: $LEADER_ID\n"

        LEADER_INDEX=$(($LEADER_ID-1))
        LEADER_IP="${HOSTS[$LEADER_INDEX]}"

        START_TIME=$(date +%s%N)

        # Replace every host except the leader
        for (( HOST_ID=$NUM_HOSTS; HOST_ID>=1; HOST_ID-- )); do
            if [[ "$HOST_ID" != "$LEADER_ID" ]]; then
                HOST_INDEX=$(($HOST_ID-1))
                HOST="${HOSTS[$HOST_INDEX]}"

                NEW_ID=$(($NUM_HOSTS+$HOST_ID))
                NEW_PORT=$((9000+$NEW_ID))
                printf "\t\tCreating server $NEW_ID on $HOST:$NEW_PORT\n"
                echo "{\"logIndex\":0,\"lastLogIndex\":0,\"servers\":[{\"id\": $NEW_ID,\"endpoint\": \"tcp://$HOST:$NEW_PORT\"}]}" > tmpCluster.json
                scp "tmpCluster.json" "ec2-user@$HOST:/home/ec2-user/Projects/baseImplementation/experiments/init-cluster.json" > /dev/null 2>&1
                ssh -f "ec2-user@${HOST}" "sh -c 'cd /home/ec2-user/Projects/baseImplementation/experiments/; nohup ./addServer.sh --id ${NEW_ID} > stdout-log.log 2>&1 &'"
                
                # client needs to remove HOST_ID and replace it with server id NUM_HOSTS + HOST_ID
                ../addClient.sh --seedIp "$LEADER_IP" --seedId "$LEADER_ID" --addRemoveServer --serverIp "$HOST" --serverId "$HOST_ID" --newServerId "$NEW_ID" > /dev/null 2>&1
            
                rm tmpCluster.json

                SEED_IP="$HOST"
                SEED_ID="$NEW_ID"
            fi
        done

        # kill the leader
        ssh -f "ec2-user@${LEADER_IP}" "killall -9 java"

        # wait until new leader is elected
        RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./ConfigChangeTest/cmd.txt)
        NEW_LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        # loop until leader is determined
        while [ "$NEW_LEADER_ID" = "-1" ] || [ "$NEW_LEADER_ID" = "$LEADER_ID" ]; do
            sleep .5
            RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./ConfigChangeTest/cmd.txt)
            NEW_LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
        done 

        printf "\t\tNEW LEADER ID: $NEW_LEADER_ID\n"

        # calculate new leader ip
        NEW_LEADER_INDEX=$(($NEW_LEADER_ID-$NUM_HOSTS))
        NEW_LEADER_INDEX=$(($NEW_LEADER_INDEX-1))
        NEW_LEADER_IP="${HOSTS[$NEW_LEADER_INDEX]}"        

        # remove and replace old leader in the configuration
        NEW_ID=$(($NUM_HOSTS+$LEADER_ID))
        NEW_PORT=$((9000+$NEW_ID))
        printf "\t\tCreating server $NEW_ID on $LEADER_IP:$NEW_PORT\n"
        echo "{\"logIndex\":0,\"lastLogIndex\":0,\"servers\":[{\"id\": $NEW_ID,\"endpoint\": \"tcp://$LEADER_IP:$NEW_PORT\"}]}" > tmpCluster.json
        scp "tmpCluster.json" "ec2-user@$LEADER_IP:/home/ec2-user/Projects/baseImplementation/experiments/init-cluster.json" > /dev/null 2>&1
        ssh -f "ec2-user@${LEADER_IP}" "sh -c 'cd /home/ec2-user/Projects/baseImplementation/experiments/; nohup ./addServer.sh --id ${NEW_ID} > stdout-log.log 2>&1 &'"
        
        # client needs to remove HOST_ID and replace it with server id NUM_HOSTS + HOST_ID
        ../addClient.sh --seedIp "$NEW_LEADER_IP" --seedId "$NEW_LEADER_ID" --addRemoveServer --serverIp "$LEADER_IP" --serverId "$LEADER_ID" --newServerId "$NEW_ID" > /dev/null 2>&1
        rm tmpCluster.json


        END_TIME=$(date +%s%N)

        # calculate difference between timestamps and convert to milliseconds
        DIFF=$(bc <<< "$END_TIME - $START_TIME")
        MS=$(bc <<< "$DIFF / 1000000")
        printf "\tMS: $MS\n"
        TEST_TIMES+=("$MS")

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

    echo "TOTAL: $TOTAL"

    AVG=$(bc <<< "$TOTAL / $NUM_TESTS")

    echo "AVG for $NUM_HOSTS hosts: $AVG"

done