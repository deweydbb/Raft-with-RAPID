#!/bin/bash
cd "$(dirname "$0")" || exit

JAR="kvstore.jar"

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
    --help)
      echo "Options:"
      printf "\t-j or --jar optional. Specifies the location of the jar file to run. Default is kvstore.jar"
      printf "\t-i or --id required. specifies the id (and ports) of the server to run\n"
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

DIR="./server${ID}"

if [ -d "$DIR" ]; then
  cd "$DIR" || exit
  rm -rf *
  cd - || exit
else
  mkdir "$DIR"
fi

cp "$JAR" "$DIR/kvstore.jar"
cp init-cluster.json "./server${ID}/cluster.json"
echo "server.id=${ID}" > "$DIR/config.properties"

cd "$DIR" || exit
java -jar "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED" "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" -jar "kvstore.jar" "server" "." "$ID"