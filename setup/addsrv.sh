#!/bin/bash

if [ $# -ne 2 ]
  then
    echo "Server id and number of servers in the cluster is required"
    exit
fi

if [ -d "./server$1" ]; then
  echo "server$1 already exists"
  exit
fi

mkdir "server$1"
echo "server.id=$1" > "./server$1/config.properties"
echo "start server$1"
cd "./server$1" || exit
gnome-terminal --title="server$1" -- java -jar ../kvstore.jar server "." "$1" "$2" "localhost" "1"
cd ..

