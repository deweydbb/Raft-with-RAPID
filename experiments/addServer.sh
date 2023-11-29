#!/bin/bash
cd "$(dirname "$0")" || exit

JAR="kvstore.jar"
SEED_IP="127.0.0.1"
SEED_ID=1
START_COUNT=0
END_COUNT=10

while [[ $# -gt 0 ]]; do
  case $1 in
    -j|--jar)
      JAR="$2"
      shift # past argument
      shift # past value
      ;;
    -i|--id)
      ID="$2"
      shift # past argument
      shift # past value
      ;;
    -s|--size)
      SIZE="$2"
      shift # past argument
      shift # past value
      ;;
    --seedIp)
      SEED_IP="$2"
      shift # past argument
      shift # past value
      ;;
    --seedId)
      SEED_ID="$2"
      shift # past argument
      shift # past value
      ;;
    --startCount)
      START_COUNT="$2"
      shift # past argument
      shift # past value
      ;;
    --endCount)
      END_COUNT="$2"
      shift # past argument
      shift # past value
      ;;
    --help)
      echo "Options:"
      printf "\t-j or --jar optional. Specifies the location of the jar file to run. Default is kvstore.jar"
      printf "\t-i or --id required. specifies the id (and ports) of the server to run\n"
      printf "\t-s or --size required. Specifies the max cluster size of the new server\n"
      printf "\t--seedIp optional. Specifies the ip address of the server to contact to join to the cluster. Default is 127.0.0.1\n"
      printf "\t--seedId optional Specifies the id (and ports) of the server to contact to join the cluster. Default is 1\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [ -z ${ID+x} ]; then
  echo "id is required"
  exit
fi

if [ -z ${SIZE+x} ]; then
  echo "size is required"
  exit
fi

DIR="./server${ID}"

if [ -d "$DIR" ]; then
  cd "$DIR" || exit
  rm -rf *
  cd - || exit
else
  mkdir "$DIR"
fi

cp "$JAR" "$DIR/kvstore.jar"
echo "server.id=${ID}" > "$DIR/config.properties"

cd "$DIR" || exit
java -jar "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -jar "kvstore.jar" "server" "." "$ID" "$SIZE" "$SEED_IP" "$SEED_ID" "$START_COUNT" "$END_COUNT"
