#!/bin/bash

HOSTS=("trouble.cs.utexas.edu" "chess.cs.utexas.edu" "pops.cs.utexas.edu")
NUM_HOSTS="${#HOSTS[@]}"
# Loop through the array
for INDEX in "${!HOSTS[@]}"
do
   ID=$((INDEX + 1))
   HOST="${HOSTS[$INDEX]}"
   echo "Creating server $ID on $HOST"
   gnome-terminal --title="$HOST:server${ID}" -- "/usr/bin/ssh" "davidk@${HOST}" -t "~/Desktop/Distributed/jraft/experiments/addServer.sh --id ${ID} --size $NUM_HOSTS --seedIp ${HOSTS[0]}"
   sleep 1
   #"cd ~/Desktop/Distributed/jraft/experiments; ./addServer "
done