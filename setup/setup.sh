#!/bin/bash

if [ $# -eq 0 ]
  then
    ./cleanup.sh
fi

START=1
NUM_SERVERS=3

for (( i=START; i<=NUM_SERVERS; i++))
do
  mkdir "server${i}"
  echo "server.id=${i}" > "./server${i}/config.properties"
  echo "start server${i}"
  cd "./server${i}" || exit
  gnome-terminal --title="server${i}" -- ../runServer.sh "$i" "$NUM_SERVERS" "127.0.0.1" "1"
  cd ..
done

sleep 2

echo "start client"
mkdir client
cp "./server1/config.properties" "./client/config.properties"
cd "./client" || exit
gnome-terminal --title="client" -- java -jar ../kvstore.jar client "." "127.0.0.1" 1
cd ..