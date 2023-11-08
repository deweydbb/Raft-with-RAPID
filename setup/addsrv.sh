#!/bin/bash

if [ $# -eq 0 ]
  then
    echo "Server id is required"
    exit
fi

if [ -d "./server$1" ]; then
  echo "server$1 already exists"
  exit
fi

mkdir "server$1"
echo "{\"logIndex\":0,\"lastLogIndex\":0,\"servers\":[{\"id\": $1,\"endpoint\": \"tcp://localhost:900$1\"}]}" > "./server$1/cluster.json"
echo "server.id=$1" > "./server$1/config.properties"
echo "start server$1"
cd "./server$1" || exit
gnome-terminal --title="server$1" -- java -jar ../kvstore.jar server "." "800$1"
cd ..

