#!/bin/bash

NUM_TESTS=5
HOST_FILES=("../hosts3.txt" "../hosts5.txt" "../hosts7.txt" "../hosts9.txt")

for HOST_FILE in "${HOST_FILES[@]}"; do 
    echo "STARTING TESTS WITH HOST FILE $HOST_FILE"
    HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))
    NUM_HOSTS="${#HOSTS[@]}"

    LARGEST_MINORITY=$(($NUM_HOSTS/2))

    TEST_TIMES=()

    for TEST_NUM in $(seq 1 $NUM_TESTS); do
        printf "\tSTART OF TEST $TEST_NUM\n"
        # create a new cluster
        ../runRemote.sh --file "$HOST_FILE" > /dev/null 2>&1
        sleep 7

        START_TIME=$(date +%s%N)

        for (( ROUND=0; ROUND<3; ROUND++ )); do 
            SUB=$(($LARGEST_MINORITY * $ROUND))
            START_ID=$(($NUM_HOSTS-$SUB))
            SUB=$(($SUB+$LARGEST_MINORITY))
            END_ID=$(($NUM_HOSTS-$SUB))
            if (( $END_ID < 0 )); then
                END_ID=0
            fi

            # kill servers
            for (( HOST_ID=$START_ID; HOST_ID>$END_ID; HOST_ID-- )); do
                printf "\t\tKilling $HOST_ID\n"
                HOST_INDEX=$(($HOST_ID-1))
                HOST="${HOSTS[$HOST_INDEX]}"

                OUTPUT=$(ssh -f "ec2-user@${HOST}" "killall -9 java")
            done

            SEED_IP="${HOSTS[0]}"
            SEED_ID=1

            if [ $START_ID -eq $SEED_ID ]; then
                INDEX=$(($NUM_HOSTS-1))
                SEED_IP="${HOSTS[$INDEX]}"
                SEED_ID=$(($NUM_HOSTS*2))
            fi

            # add servers
            for (( HOST_ID=$START_ID; HOST_ID>$END_ID; HOST_ID-- )); do
                HOST_INDEX=$(($HOST_ID-1))
                HOST="${HOSTS[$HOST_INDEX]}"
                NEW_ID=$(($HOST_ID+$NUM_HOSTS))
                printf "\t\tCreating server $NEW_ID on $HOST\n"
                
                ssh -f "ec2-user@${HOST}" "sh -c 'cd /home/ec2-user/Projects/jraft/experiments/; nohup ./addServer.sh --id ${NEW_ID} --size $NUM_HOSTS --seedIp $SEED_IP --seedId $SEED_ID > stdout-log.log 2>&1 &'"
            done

            SEED_IP="${HOSTS[0]}"
            SEED_ID=1

            if [ $START_ID -eq $SEED_ID ]; then
                INDEX=$(($NUM_HOSTS-1))
                SEED_IP="${HOSTS[$INDEX]}"
                SEED_ID=$(($NUM_HOSTS*2))
            fi

            printf "\t\tSeedIp $SEED_IP, seedId $SEED_ID\n"

            # wait for changes to occur
            while [ -z "${CHANGE_COMPLETED}" ]; do
                sleep .1
                RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./ConfigChangeTest/cmd.txt)
                CONFIG=$(echo "$RAW_OUTPUT" | grep -Eo "\[.*\]" | grep -Eo "id: [0-9]+," | grep -Eo "[0-9]+")
                printf "CONFIG\n$CONFIG\n"
                CHANGE_COMPLETED="TRUE"

                for (( HOST_ID=$START_ID; HOST_ID>$END_ID; HOST_ID-- )); do
                    NEW_ID=$(($HOST_ID+$NUM_HOSTS))
                    HOST_REMOVED=$(echo "$CONFIG" | grep "^$HOST_ID$")
                    HOST_ADDED=$(echo "$CONFIG" | grep "^$NEW_ID$")

                    printf "\t\tHost removed $HOST_REMOVED, host added $HOST_ADDED\n"

                    # if host_removed exists or host_added does not exist
                    if [ -n "$HOST_REMOVED" ] || [ -z "${HOST_ADDED}" ]; then 
                        CHANGE_COMPLETED=""
                        break;
                    fi
                done
            done
            CHANGE_COMPLETED=""
        done

        END_TIME=$(date +%s%N)

        #calculate difference between timestamps and convert to milliseconds
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