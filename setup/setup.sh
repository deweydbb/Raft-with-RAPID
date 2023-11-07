#!/bin/bash

for i in 1 2 3
do
  mkdir "server${i}"
  cp init-cluster.json "./server${i}/cluster.json"
  echo "server.id=${i}" > "./server${i}/config.properties"
  echo "start server${i}"
  cd "./server${i}" || exit
  gnome-terminal --title="server${i}" -- java -jar ../kvstore.jar server "." "800$i"
  cd ..
done

echo "start client"
mkdir client
cp init-cluster.json "./client/cluster.json"
cp "./server1/config.properties" "./client/config.properties"
cd "./client" || exit
gnome-terminal --title="client" -- java -jar ../kvstore.jar client "."
cd ..