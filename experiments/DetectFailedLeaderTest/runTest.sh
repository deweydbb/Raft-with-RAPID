#!/bin/bash


NUM_TESTS=5
HOST_FILE="../hosts3.txt"

../runRemote.sh --file "$HOST_FILE" --cluster ../init-cluster.json
sleep 3

SEED_IP=$(head -n 1 "$HOST_FILE")
RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --command ./DetectFailedLeaderTest/cmd.txt)

LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
LEADER_INDEX=$(($LEADER_ID-1))
echo "Current leader is: $LEADER_ID"

HOSTS=($(cat "$HOST_FILE" | tr "\n" " "))

LEADER_IP="${HOSTS[$LEADER_INDEX]}"

KILL_TIME=$(date +%s%N)
ssh -f "ec2-user@${LEADER_IP}" "killall -9 java"

echo "$KILL_TIME"

SEED_ID=1
if [ "$SEED_ID" = "$LEADER_ID" ]; then
  SEED_ID=$(($SEED_ID+1))
fi
SEED_INDEX=$(($SEED_ID-1))
SEED_IP="${HOSTS[$SEED_INDEX]}"

echo "seedId is $SEED_ID, seedIP is $SEED_IP"

NEW_LEADER_ID=-1

while [ "$NEW_LEADER_ID" = "-1" ] || [ "$NEW_LEADER_ID" = "$LEADER_ID" ]; do
  RAW_OUTPUT=$(../addClient.sh --seedIp "$SEED_IP" --seedId "$SEED_ID" --command ./DetectFailedLeaderTest/cmd.txt)
  NEW_LEADER_ID=$(echo "$RAW_OUTPUT" | grep "Leader" | grep -Eo "[-]?[0-9]+")
  sleep 1
done 

echo "new leader is $NEW_LEADER_ID"

NEW_LEADER_INDEX=$(($SEED_ID-1))
NEW_LEADER_IP="${HOSTS[$SEED_INDEX]}"


DEBUG_CONTENTS=$(ssh -f "ec2-user@${NEW_LEADER_IP}" "cat /home/ec2-user/Projects/baseImplementation/experiments/server${NEW_LEADER_ID}/raft-debugging.log")


echo "$DEBUG_CONTENTS"


../runRemoteKillServer.sh --file "$HOST_FILE" 