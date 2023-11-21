#!/bin/bash

while [[ $# -gt 0 ]]; do
  case $1 in
    --file)
      FILE="$2"
      shift
      shift
      ;;
    --help)
      echo "Options:"
      printf "--file Required. Specifies a file containing a list on hosts, one on each line to run addServer on\n"
      exit
      ;;
    -*|--*)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

if [[ -n ${FILE+x} ]]; then
  readarray -t HOSTS < "$FILE"
  NUM_HOSTS="${#HOSTS[@]}"
else
  echo "--file is required"
  exit
fi

# Loop through the array
for INDEX in "${!HOSTS[@]}"
do
   ID=$((INDEX + 1))
   HOST="${HOSTS[$INDEX]}"
   echo "Creating server $ID on $HOST"
   gnome-terminal --title="$HOST:server${ID}" -- "/usr/bin/ssh" "davidk@${HOST}" -t "~/Desktop/Distributed/jraft/experiments/addServer.sh --id ${ID} --size $NUM_HOSTS --seedIp ${HOSTS[0]}"
   sleep 1
done