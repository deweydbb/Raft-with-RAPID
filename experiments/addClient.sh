#!/bin/bash
cd "$(dirname "$0")" || exit

JAR="kvstore.jar"
SEED_IP="127.0.0.1"
SEED_ID=1
DIR="./client"

while [[ $# -gt 0 ]]; do
  case $1 in
    -j|--jar)
      JAR="$2"
      shift # past argument
      shift # past value
      ;;
    -d|--directory)
      DIR="$2"
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
    --command)
      CMD="$2"
      shift
      shift
      ;;
    --throughput)
      THROUGHPUT="TRUE"
      shift
      ;;
    --numPuts)
      NUM_PUTS="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "\t-j or --jar optional. Specifies the location of the jar file to run. Default is kvstore.jar\n"
      printf "\t-d or --directory optional. specifies the working directory of the server. Default is ./server{ID} \n"
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


if [ -d "$DIR" ]; then
  cd "$DIR" || exit
  rm -rf *
  cd - || exit
else
  mkdir "$DIR"
fi

if [[ -n ${THROUGHPUT+x} ]] && [ -z ${NUM_PUTS+x} ]; then
    echo "--numPuts is required with --throughput"
    exit
fi

if [[ -n ${CMD+x} ]] && [[ -n ${THROUGHPUT+x} ]]; then
    echo "Both --command and --throughput cannot both be used"
    exit
fi

cp "$JAR" "$DIR/kvstore.jar"
echo "{\"logIndex\":0,\"lastLogIndex\":0,\"servers\":[{\"id\": $SEED_ID,\"endpoint\": \"tcp://$SEED_IP:900$SEED_ID\"}]}" > "$DIR/cluster.json"
echo "server.id=${SEED_ID}" > "$DIR/config.properties"
if [[ -n ${CMD+x} ]]; then
    cp "$CMD" "$DIR/cmd.txt"
fi

cd "$DIR" || exit

if [[ -n ${CMD+x} ]]; then
  java -jar "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -jar "kvstore.jar" "client" "." "$SEED_IP" "$SEED_ID" < cmd.txt
elif [[ -n ${THROUGHPUT+x} ]]; then
  java -jar "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -jar "kvstore.jar" "client" "." "$SEED_IP" "$SEED_ID" throughput "$NUM_PUTS"
else
  java -jar "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -jar "kvstore.jar" "client" "." "$SEED_IP" "$SEED_ID"
fi
